package com.yupi.springbootinit.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/** 每日 AI 用量趋势项。 */
@Data
public class AiUsageDailyTrendVO implements Serializable {
    private String date;
    private Long calls;
    private Long totalTokens;
    private BigDecimal estimatedCostCny;

    private static final long serialVersionUID = 1L;
}
