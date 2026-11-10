package com.yupi.springbootinit.modules.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 AI 选择的分析方案；模型输出不会直接作为 SQL 或图表数据执行。 */
@Component
public class AnalysisPlanParser {
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");
    private final ObjectMapper objectMapper;

    public AnalysisPlanParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AnalysisPlan parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("模型没有返回分析方案");
        }
        try {
            String normalized = content.replaceFirst("^\\s*```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```\\s*$", "").trim();
            Matcher matcher = JSON_OBJECT.matcher(normalized);
            if (!matcher.find()) throw new IllegalArgumentException("模型返回的分析方案不是有效 JSON");
            JsonNode root = objectMapper.readTree(matcher.group());
            String status = text(root, "status", "READY").toUpperCase(Locale.ROOT);
            if ("NEEDS_CLARIFICATION".equals(status)) {
                String question = text(root, "clarificationQuestion", "");
                throw new IllegalArgumentException(question.isBlank()
                        ? "分析目标或表格字段不明确，请补充说明"
                        : question);
            }
            if (!"READY".equals(status)) throw new IllegalArgumentException("模型返回了无法识别的分析状态");

            AnalysisPlan plan = new AnalysisPlan();
            plan.setDimensionField(text(root, "dimensionField", ""));
            String metricField = text(root, "metricField", "");
            plan.setMetricField("null".equalsIgnoreCase(metricField) ? "" : metricField);
            plan.setAggregation(text(root, "aggregation", "").toUpperCase(Locale.ROOT));
            plan.setChartType(text(root, "chartType", "bar").toLowerCase(Locale.ROOT));
            plan.setChartTitle(text(root, "chartTitle", ""));
            plan.setReason(text(root, "reason", ""));
            if (plan.getDimensionField().isBlank()) throw new IllegalArgumentException("模型没有指定分组字段");
            if (!plan.getAggregation().matches("COUNT_ROWS|COUNT_DISTINCT|SUM|AVERAGE")) {
                throw new IllegalArgumentException("模型选择了不支持的统计方式");
            }
            if (!plan.getChartType().matches("bar|line|pie|radar")) {
                throw new IllegalArgumentException("模型选择了不支持的图表类型");
            }
            return plan;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析模型返回的分析方案", e);
        }
    }

    private String text(JsonNode root, String key, String fallback) {
        JsonNode value = root.get(key);
        return value == null || value.isNull() ? fallback : value.asText(fallback).trim();
    }
}
