package com.yupi.springbootinit.modules.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 将模型文本解析为分析业务结果；兼容 JSON、Markdown 和历史分隔符。 */
@Component
public class AnalysisResultParser {
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");
    private static final String DELIMITER = "【【【【【";
    private final ObjectMapper objectMapper;

    public AnalysisResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 解析并严格校验 genChart/genResult，失败时抛出 IllegalArgumentException。 */
    public AnalysisResult parse(String content) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("AI 返回内容为空");
        String normalized = stripMarkdown(content).trim();
        AnalysisResult jsonResult = tryParseJson(normalized);
        if (jsonResult != null) return jsonResult;
        String[] parts = normalized.split(Pattern.quote(DELIMITER), -1);
        if (parts.length >= 3) {
            String chart = parts[1].trim();
            String result = parts[2].trim();
            validateChart(chart);
            if (result.isBlank()) throw new IllegalArgumentException("genResult 为空");
            return new AnalysisResult(chart, result);
        }
        throw new IllegalArgumentException("AI 返回结果不是有效分析 JSON");
    }

    private AnalysisResult tryParseJson(String content) {
        try {
            Matcher matcher = JSON_OBJECT.matcher(content);
            if (!matcher.find()) return null;
            JsonNode root = objectMapper.readTree(matcher.group());
            JsonNode chart = root.get("genChart");
            JsonNode result = root.get("genResult");
            if (result == null || !result.isTextual() || result.asText().isBlank()) return null;
            // 新契约只要求模型提供结论；图表由本地统计服务生成。保留旧 JSON 的兼容解析。
            if (chart == null) return new AnalysisResult(null, result.asText());
            if (!chart.isObject()) return null;
            String chartJson = objectMapper.writeValueAsString(chart);
            validateChart(chartJson);
            return new AnalysisResult(chartJson, result.asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stripMarkdown(String content) {
        return content.replaceFirst("^\\s*```(?:json)?\\s*", "")
                .replaceFirst("\\s*```\\s*$", "");
    }

    private void validateChart(String chartJson) {
        try {
            JsonNode node = objectMapper.readTree(chartJson);
            if (!node.isObject()) throw new IllegalArgumentException("genChart 必须是 JSON 对象");
            String raw = chartJson.toLowerCase();
            if (raw.contains("undefined") || raw.contains("nan") || raw.contains("function")) {
                throw new IllegalArgumentException("genChart 包含非法内容");
            }
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
            throw new IllegalArgumentException("genChart 不是有效 JSON", e);
        }
    }
}
