package com.yupi.springbootinit.airuntime.resilience;

import com.yupi.springbootinit.airuntime.api.AiChatClient;
import com.yupi.springbootinit.airuntime.api.AiChatRequest;
import com.yupi.springbootinit.airuntime.api.AiChatResponse;
import com.yupi.springbootinit.airuntime.api.AiCostCalculator;
import com.yupi.springbootinit.airuntime.config.AiRuntimeProperties;
import com.yupi.springbootinit.airuntime.exception.AiRuntimeErrorCode;
import com.yupi.springbootinit.airuntime.exception.AiRuntimeException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 弹性 AI 客户端与成本计算器单元测试。 */
class ResilientAiChatClientTest {

    @Test
    void shouldSucceedOnFirstAttemptWithoutFallback() {
        AiChatClient delegate = mock(AiChatClient.class);
        when(delegate.chat(any())).thenReturn(response("glm-4-plus", "ok"));
        List<Long> sleeps = new ArrayList<>();
        ResilientAiChatClient client = new ResilientAiChatClient(delegate, properties(), sleeps::add, () -> 0.5d);

        AiChatResponse result = client.chat(request());

        assertEquals("ok", result.getContent());
        assertEquals(1, result.getAttemptCount());
        assertFalse(result.isFallbackUsed());
        assertNotNull(result.getLatencyMs());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void shouldRetryWithExponentialBackoffOnRetryableErrors() {
        AiChatClient delegate = mock(AiChatClient.class);
        when(delegate.chat(any()))
                .thenThrow(new AiRuntimeException(AiRuntimeErrorCode.AI_RATE_LIMITED, true, "429"))
                .thenThrow(new AiRuntimeException(AiRuntimeErrorCode.AI_UPSTREAM_UNAVAILABLE, true, "503"))
                .thenReturn(response("glm-4-plus", "recovered"));
        List<Long> sleeps = new ArrayList<>();
        ResilientAiChatClient client = new ResilientAiChatClient(delegate, properties(), sleeps::add, () -> 0.5d);

        AiChatResponse result = client.chat(request());

        assertEquals("recovered", result.getContent());
        assertEquals(3, result.getAttemptCount());
        assertFalse(result.isFallbackUsed());
        assertEquals(List.of(100L, 200L), sleeps);
    }

    @Test
    void shouldFailFastWithoutRetryOrFallbackOnAuthenticationError() {
        AiChatClient delegate = mock(AiChatClient.class);
        when(delegate.chat(any()))
                .thenThrow(new AiRuntimeException(AiRuntimeErrorCode.AI_AUTHENTICATION_ERROR, false, "401"));
        List<Long> sleeps = new ArrayList<>();
        ResilientAiChatClient client = new ResilientAiChatClient(delegate, properties(), sleeps::add, () -> 0.5d);

        AiRuntimeException ex = assertThrows(AiRuntimeException.class, () -> client.chat(request()));

        assertEquals(AiRuntimeErrorCode.AI_AUTHENTICATION_ERROR, ex.getErrorCode());
        verify(delegate, times(1)).chat(any());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void shouldFallbackToSecondaryModelAfterPrimaryRetriesExhausted() {
        AiChatClient delegate = mock(AiChatClient.class);
        when(delegate.chat(any()))
                .thenThrow(new AiRuntimeException(AiRuntimeErrorCode.AI_UPSTREAM_UNAVAILABLE, true, "503-1"))
                .thenThrow(new AiRuntimeException(AiRuntimeErrorCode.AI_UPSTREAM_UNAVAILABLE, true, "503-2"))
                .thenThrow(new AiRuntimeException(AiRuntimeErrorCode.AI_UPSTREAM_UNAVAILABLE, true, "503-3"))
                .thenReturn(response("glm-4-flash", "fallback-ok"));
        List<Long> sleeps = new ArrayList<>();
        ResilientAiChatClient client = new ResilientAiChatClient(delegate, properties(), sleeps::add, () -> 0.5d);

        AiChatResponse result = client.chat(request());

        assertEquals("fallback-ok", result.getContent());
        assertEquals("glm-4-flash", result.getModel());
        assertEquals(4, result.getAttemptCount());
        assertTrue(result.isFallbackUsed());

        ArgumentCaptor<AiChatRequest> captor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(delegate, times(4)).chat(captor.capture());
        assertEquals("glm-4-plus", captor.getAllValues().get(0).getModel());
        assertEquals("glm-4-plus", captor.getAllValues().get(2).getModel());
        assertEquals("glm-4-flash", captor.getAllValues().get(3).getModel());
    }

    @Test
    void shouldEstimateCostCnyAccurately() {
        AiRuntimeProperties props = properties();

        BigDecimal flashCost = AiCostCalculator.estimateCostCny(props, "glm-4-flash", 1000, 500);
        BigDecimal plusCost = AiCostCalculator.estimateCostCny(props, "glm-4-plus", 2000, 1000);
        BigDecimal unknownCost = AiCostCalculator.estimateCostCny(props, "unknown-model", 1000, 500);

        assertEquals(new BigDecimal("0.000150"), flashCost);
        assertEquals(new BigDecimal("0.150000"), plusCost);
        assertEquals(new BigDecimal("0.000000"), unknownCost);
    }

    private AiRuntimeProperties properties() {
        AiRuntimeProperties props = new AiRuntimeProperties();
        props.setDefaultModel("glm-4-plus");
        props.setAllowedModels(List.of("glm-4-plus", "glm-4-flash"));
        props.setFallbackModels(List.of("glm-4-flash"));
        props.getRetry().setMaxRetries(2);
        props.getRetry().setInitialBackoffMs(100L);
        props.getRetry().setMaxBackoffMs(500L);
        props.getRetry().setJitterRatio(0.0d);
        return props;
    }

    private AiChatRequest request() {
        AiChatRequest request = new AiChatRequest();
        request.setUserMessage("测试提示词");
        request.setTraceId("trace-1");
        return request;
    }

    private AiChatResponse response(String model, String content) {
        AiChatResponse response = new AiChatResponse();
        response.setModel(model);
        response.setContent(content);
        return response;
    }
}
