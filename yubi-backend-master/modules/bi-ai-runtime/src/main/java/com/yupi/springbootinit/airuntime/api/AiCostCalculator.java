package com.yupi.springbootinit.airuntime.api;

import com.yupi.springbootinit.airuntime.config.AiRuntimeProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/** 根据模型单价配置估算单次 AI 调用费用（人民币元，保留 6 位小数）。 */
public final class AiCostCalculator {
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final BigDecimal ZERO_COST = BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);

    private AiCostCalculator() {}

    public static BigDecimal estimateCostCny(AiRuntimeProperties properties, String model,
                                             Integer inputTokens, Integer outputTokens) {
        if (properties == null || properties.getPricing() == null || model == null || model.isBlank()) {
            return ZERO_COST;
        }
        AiRuntimeProperties.ModelPricing pricing = findPricing(properties.getPricing(), model);
        if (pricing == null) {
            return ZERO_COST;
        }
        BigDecimal inTokens = inputTokens == null || inputTokens <= 0 ? BigDecimal.ZERO : BigDecimal.valueOf(inputTokens);
        BigDecimal outTokens = outputTokens == null || outputTokens <= 0 ? BigDecimal.ZERO : BigDecimal.valueOf(outputTokens);
        BigDecimal inRate = pricing.getInputPerMillionCny() == null ? BigDecimal.ZERO : pricing.getInputPerMillionCny();
        BigDecimal outRate = pricing.getOutputPerMillionCny() == null ? BigDecimal.ZERO : pricing.getOutputPerMillionCny();

        BigDecimal inputCost = inTokens.multiply(inRate).divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        BigDecimal outputCost = outTokens.multiply(outRate).divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        return inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP);
    }

    private static AiRuntimeProperties.ModelPricing findPricing(Map<String, AiRuntimeProperties.ModelPricing> map,
                                                                String model) {
        AiRuntimeProperties.ModelPricing direct = map.get(model);
        if (direct != null) {
            return direct;
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, AiRuntimeProperties.ModelPricing> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getKey().trim().toLowerCase(Locale.ROOT).equals(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
