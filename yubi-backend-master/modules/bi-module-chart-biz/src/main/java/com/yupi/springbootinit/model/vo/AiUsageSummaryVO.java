package com.yupi.springbootinit.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/** 用户级 AI 用量、成本与降级保障汇总视图。 */
@Data
public class AiUsageSummaryVO implements Serializable {
    private Long totalCalls = 0L;
    private Long successCalls = 0L;
    private Long fallbackCalls = 0L;
    private Long totalInputTokens = 0L;
    private Long totalOutputTokens = 0L;
    private Long totalTokens = 0L;
    private BigDecimal totalEstimatedCostCny = BigDecimal.ZERO;
    private Long avgLatencyMs = 0L;
    private List<AiUsageDailyTrendVO> dailyTrend = Collections.emptyList();

    private static final long serialVersionUID = 1L;
}
