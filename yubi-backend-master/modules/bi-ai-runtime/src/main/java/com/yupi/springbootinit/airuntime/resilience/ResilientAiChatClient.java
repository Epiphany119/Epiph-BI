package com.yupi.springbootinit.airuntime.resilience;

import com.yupi.springbootinit.airuntime.api.AiChatClient;
import com.yupi.springbootinit.airuntime.api.AiChatRequest;
import com.yupi.springbootinit.airuntime.api.AiChatResponse;
import com.yupi.springbootinit.airuntime.config.AiRuntimeProperties;
import com.yupi.springbootinit.airuntime.exception.AiRuntimeErrorCode;
import com.yupi.springbootinit.airuntime.exception.AiRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * 弹性 AI 客户端装饰器：在底层供应商客户端外层统一提供指数退避重试、
 * 多模型自动 Fallback 降级以及端到端耗时/尝试次数采集。
 */
public class ResilientAiChatClient implements AiChatClient {
    private static final Logger log = LoggerFactory.getLogger(ResilientAiChatClient.class);

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final AiChatClient delegate;
    private final AiRuntimeProperties properties;
    private final Sleeper sleeper;
    private final DoubleSupplier randomSupplier;

    public ResilientAiChatClient(AiChatClient delegate, AiRuntimeProperties properties) {
        this(delegate, properties, Thread::sleep, () -> ThreadLocalRandom.current().nextDouble());
    }

    ResilientAiChatClient(AiChatClient delegate, AiRuntimeProperties properties,
                          Sleeper sleeper, DoubleSupplier randomSupplier) {
        this.delegate = delegate;
        this.properties = properties;
        this.sleeper = sleeper;
        this.randomSupplier = randomSupplier;
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        if (request == null) {
            throw new AiRuntimeException(AiRuntimeErrorCode.AI_CONFIGURATION_ERROR, false, "request 不能为空");
        }
        List<String> candidateModels = buildCandidateModels(request);
        AiRuntimeProperties.Retry retryConfig = properties.getRetry() != null
                ? properties.getRetry()
                : new AiRuntimeProperties.Retry();
        int maxRetries = Math.max(0, retryConfig.getMaxRetries());

        long startedNs = System.nanoTime();
        int totalAttempts = 0;
        AiRuntimeException lastException = null;

        for (int modelIdx = 0; modelIdx < candidateModels.size(); modelIdx++) {
            String candidateModel = candidateModels.get(modelIdx);
            boolean isFallback = modelIdx > 0;
            AiChatRequest attemptRequest = copyWithModel(request, candidateModel);

            for (int retry = 0; retry <= maxRetries; retry++) {
                totalAttempts++;
                try {
                    AiChatResponse response = delegate.chat(attemptRequest);
                    long latencyMs = Math.max(0L, (System.nanoTime() - startedNs) / 1_000_000L);
                    response.setAttemptCount(totalAttempts);
                    response.setFallbackUsed(isFallback);
                    response.setLatencyMs(latencyMs);
                    if (response.getModel() == null || response.getModel().isBlank()) {
                        response.setModel(candidateModel);
                    }
                    if (isFallback) {
                        log.warn("AI 主模型不可用，已自动降级至备用模型执行成功，model={}, attempts={}, latencyMs={}, traceId={}",
                                candidateModel, totalAttempts, latencyMs, request.getTraceId());
                    }
                    return response;
                } catch (AiRuntimeException e) {
                    lastException = e;
                    if (!e.isRetryable()) {
                        throw e;
                    }
                    if (retry < maxRetries) {
                        long backoffMs = computeBackoffMs(retry, retryConfig);
                        log.warn("AI 请求触发可重试异常（{}），将在 {}ms 后进行第 {} 次重试，model={}, traceId={}",
                                e.getErrorCode(), backoffMs, retry + 1, candidateModel, request.getTraceId());
                        sleepOrThrow(backoffMs, request.getTraceId());
                    } else if (modelIdx + 1 < candidateModels.size()) {
                        log.warn("AI 模型 {} 重试耗尽（{}），准备降级至备用模型 {}，traceId={}",
                                candidateModel, e.getErrorCode(), candidateModels.get(modelIdx + 1), request.getTraceId());
                    }
                }
            }
        }
        throw lastException != null
                ? lastException
                : new AiRuntimeException(AiRuntimeErrorCode.AI_UNKNOWN_ERROR, false, "AI 调用失败");
    }

    private List<String> buildCandidateModels(AiChatRequest request) {
        String primaryModel = request.getModel() == null || request.getModel().isBlank()
                ? properties.getDefaultModel()
                : request.getModel().trim();
        Set<String> models = new LinkedHashSet<>();
        models.add(primaryModel);
        List<String> allowed = properties.getAllowedModels();
        if (properties.getFallbackModels() != null) {
            for (String fallback : properties.getFallbackModels()) {
                if (fallback == null || fallback.isBlank()) {
                    continue;
                }
                String trimmed = fallback.trim();
                if (allowed == null || allowed.isEmpty() || allowed.contains(trimmed)) {
                    models.add(trimmed);
                }
            }
        }
        return new ArrayList<>(models);
    }

    private AiChatRequest copyWithModel(AiChatRequest source, String model) {
        AiChatRequest copy = new AiChatRequest();
        copy.setSystemPrompt(source.getSystemPrompt());
        copy.setUserMessage(source.getUserMessage());
        copy.setModel(model);
        copy.setTemperature(source.getTemperature());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setTraceId(source.getTraceId());
        return copy;
    }

    private long computeBackoffMs(int retryIndex, AiRuntimeProperties.Retry config) {
        long base = Math.max(1L, config.getInitialBackoffMs());
        long max = Math.max(base, config.getMaxBackoffMs());
        long exponential = retryIndex >= 30 ? max : Math.min(max, base * (1L << retryIndex));
        double jitterRatio = Math.max(0.0d, Math.min(1.0d, config.getJitterRatio()));
        if (jitterRatio == 0.0d) {
            return exponential;
        }
        double factor = 1.0d + (randomSupplier.getAsDouble() * 2.0d - 1.0d) * jitterRatio;
        return Math.max(1L, Math.round(exponential * factor));
    }

    private void sleepOrThrow(long millis, String traceId) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiRuntimeException(AiRuntimeErrorCode.AI_NETWORK_ERROR, true, null, null,
                    "AI 重试等待被中断", traceId, e);
        }
    }
}
