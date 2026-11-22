package com.yupi.springbootinit.modules.chart.application;

import java.math.BigDecimal;

/** 记录单次 AI 调用用量与成本的受控命令对象。 */
public class RecordAiUsageCommand {
    private final Long userId;
    private final Long chartId;
    private final String stage;
    private final String requestedModel;
    private final String actualModel;
    private final boolean fallbackUsed;
    private final int attemptCount;
    private final boolean success;
    private final String errorCode;
    private final int inputTokens;
    private final int outputTokens;
    private final int totalTokens;
    private final BigDecimal estimatedCostCny;
    private final long latencyMs;

    public RecordAiUsageCommand(Long userId, Long chartId, String stage,
                                String requestedModel, String actualModel,
                                boolean fallbackUsed, int attemptCount,
                                boolean success, String errorCode,
                                int inputTokens, int outputTokens, int totalTokens,
                                BigDecimal estimatedCostCny, long latencyMs) {
        this.userId = userId;
        this.chartId = chartId;
        this.stage = stage;
        this.requestedModel = requestedModel;
        this.actualModel = actualModel;
        this.fallbackUsed = fallbackUsed;
        this.attemptCount = attemptCount;
        this.success = success;
        this.errorCode = errorCode;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.estimatedCostCny = estimatedCostCny;
        this.latencyMs = latencyMs;
    }

    public Long getUserId() { return userId; }
    public Long getChartId() { return chartId; }
    public String getStage() { return stage; }
    public String getRequestedModel() { return requestedModel; }
    public String getActualModel() { return actualModel; }
    public boolean isFallbackUsed() { return fallbackUsed; }
    public int getAttemptCount() { return attemptCount; }
    public boolean isSuccess() { return success; }
    public String getErrorCode() { return errorCode; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public int getTotalTokens() { return totalTokens; }
    public BigDecimal getEstimatedCostCny() { return estimatedCostCny; }
    public long getLatencyMs() { return latencyMs; }
}
