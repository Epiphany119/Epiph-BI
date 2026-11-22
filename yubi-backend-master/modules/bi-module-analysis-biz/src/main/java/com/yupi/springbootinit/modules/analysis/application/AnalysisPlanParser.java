package com.yupi.springbootinit.modules.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 AI 选择的分析方案；模型输出不会直接作为 SQL 或图表数据执行。 */
@Component
public class AnalysisPlanParser {
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");
    private static final Set<String> ALLOWED_OPERATORS = Set.of(
            "EQUALS", "NOT_EQUALS", "IN", "CONTAINS", "GT", "GTE", "LT", "LTE");
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
            String seriesField = text(root, "seriesField", "");
            plan.setSeriesField("null".equalsIgnoreCase(seriesField) ? "" : seriesField);
            String metricField = text(root, "metricField", "");
            plan.setMetricField("null".equalsIgnoreCase(metricField) ? "" : metricField);
            plan.setAggregation(text(root, "aggregation", "").toUpperCase(Locale.ROOT));
            plan.setChartType(normalizeChartType(text(root, "chartType", "bar")));
            plan.setChartTitle(text(root, "chartTitle", ""));
            plan.setReason(text(root, "reason", ""));
            plan.setFilters(parseFilters(root.get("filters")));
            if (plan.getDimensionField().isBlank()) throw new IllegalArgumentException("模型没有指定分组字段");
            if (!plan.getAggregation().matches("COUNT_ROWS|COUNT_DISTINCT|SUM|AVERAGE")) {
                throw new IllegalArgumentException("模型选择了不支持的统计方式");
            }
            if (!plan.getChartType().matches("bar|line|pie|radar|stacked")) {
                throw new IllegalArgumentException("模型选择了不支持的图表类型");
            }
            if ("stacked".equals(plan.getChartType()) && plan.getSeriesField().isBlank()) {
                throw new IllegalArgumentException("模型没有指定堆叠系列字段");
            }
            return plan;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析模型返回的分析方案", e);
        }
    }

    private List<FilterCondition> parseFilters(JsonNode filtersNode) {
        List<FilterCondition> result = new ArrayList<>();
        if (filtersNode == null || filtersNode.isNull() || !filtersNode.isArray()) {
            return result;
        }
        for (JsonNode item : filtersNode) {
            if (item == null || !item.isObject()) continue;
            String field = text(item, "field", "");
            String operator = text(item, "operator", "EQUALS").toUpperCase(Locale.ROOT);
            if (field.isBlank()) {
                throw new IllegalArgumentException("模型返回了缺少字段名的过滤条件");
            }
            if (!ALLOWED_OPERATORS.contains(operator)) {
                throw new IllegalArgumentException("模型返回了不支持的过滤操作符: " + operator);
            }
            List<String> values = new ArrayList<>();
            JsonNode valuesNode = item.get("values");
            if (valuesNode != null && valuesNode.isArray()) {
                for (JsonNode val : valuesNode) {
                    if (val != null && !val.isNull()) {
                        String textVal = val.asText("").trim();
                        if (!textVal.isEmpty()) values.add(textVal);
                    }
                }
            } else if (item.hasNonNull("value")) {
                String single = item.get("value").asText("").trim();
                if (!single.isEmpty()) values.add(single);
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException("过滤条件“" + field + "”缺少有效过滤值");
            }
            result.add(new FilterCondition(field, operator, values));
        }
        return result;
    }

    private String text(JsonNode root, String key, String fallback) {
        JsonNode value = root.get(key);
        return value == null || value.isNull() ? fallback : value.asText(fallback).trim();
    }

    private String normalizeChartType(String chartType) {
        String normalized = chartType == null ? "bar" : chartType.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("堆叠")) return "stacked";
        if (normalized.contains("折线")) return "line";
        if (normalized.contains("饼")) return "pie";
        if (normalized.contains("雷达")) return "radar";
        if (normalized.contains("柱")) return "bar";
        return normalized;
    }
}
