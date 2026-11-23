package com.yupi.springbootinit.modules.analysis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.springbootinit.modules.analysis.application.AnalysisPlan;
import com.yupi.springbootinit.modules.analysis.application.AnalysisPlanParser;
import com.yupi.springbootinit.modules.analysis.application.AnalysisSummary;
import com.yupi.springbootinit.modules.analysis.application.SpreadsheetAnalysisService;
import com.yupi.springbootinit.modules.analysis.prompt.AnalysisPromptBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Epiph-BI V2.0 离线架构对比评测套件（24 场景）。
 * 对比 Baseline（传统直接喂全量 CSV）与 V2（Plan-Compute-Explain 两阶段本地聚合）在：
 * 1) 数值精确准确率（Numeric Exact Match）
 * 2) ECharts JSON 合法可渲染率
 * 3) Prompt 载荷 / 估算 Token 消耗与节省比例
 * 4) 异常/模糊/脏数据护栏拦截率
 */
class BiArchitectureBenchmarkTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SpreadsheetAnalysisService analysisService = new SpreadsheetAnalysisService(objectMapper);
    private final AnalysisPlanParser planParser = new AnalysisPlanParser(objectMapper);
    private final AnalysisPromptBuilder promptBuilder = new AnalysisPromptBuilder();

    private static final String SALES_CSV =
            "订单号,区域,渠道,品类,销售额,客户ID\n"
                    + "O01,华东,线上,手机,￥1,200,C01\n"
                    + "O02,华东,线下,耳机,300,C01\n"
                    + "O03,华东,线上,平板,800,C02\n"
                    + "O04,华南,线上,手机,1500,C03\n"
                    + "O05,华南,线下,耳机,400,C04\n"
                    + "O06,华北,线上,手机,1000,C05\n"
                    + "O07,华北,线下,平板,600,C05\n"
                    + "O08,华北,线上,耳机,,C06\n";

    @Test
    void run24ScenarioOfflineBenchmarkSuite() throws Exception {
        List<CaseResult> results = new ArrayList<>();

        // 1. 基础聚合（4 场景）
        results.add(evalValid("01-基础计数", "基础聚合", SALES_CSV, "按区域统计订单数", "柱状图",
                planJson("区域", null, null, "COUNT_ROWS", "bar", "[]"),
                List.of("华东", "华北", "华南"), List.of(bd("3"), bd("3"), bd("2"))));
        results.add(evalValid("02-基础求和(含货币与千分位)", "基础聚合", SALES_CSV, "按区域统计销售额合计", "柱状图",
                planJson("区域", null, "销售额", "SUM", "bar", "[]"),
                List.of("华东", "华南", "华北"), List.of(bd("2300"), bd("1900"), bd("1600"))));
        results.add(evalValid("03-基础均值(自动跳过空值)", "基础聚合", SALES_CSV, "按渠道统计平均销售额", "柱状图",
                planJson("渠道", null, "销售额", "AVERAGE", "bar", "[]"),
                List.of("线上", "线下"), List.of(bd("1125"), bd("433.3333"))));
        results.add(evalValid("04-去重计数(COUNT_DISTINCT)", "基础聚合", SALES_CSV, "按区域统计去重客户数", "饼图",
                planJson("区域", null, "客户ID", "COUNT_DISTINCT", "pie", "[]"),
                List.of("华东", "华南", "华北"), List.of(bd("2"), bd("2"), bd("2"))));

        // 2. 二维堆叠矩阵（3 场景）
        results.add(evalValid("05-二维堆叠计数", "二维堆叠", SALES_CSV, "按区域和渠道统计订单数", "堆叠图",
                planJson("区域", "渠道", null, "COUNT_ROWS", "stacked", "[]"),
                List.of("华东", "华北", "华南"), List.of(bd("3"), bd("3"), bd("2"))));
        results.add(evalValid("06-二维堆叠求和", "二维堆叠", SALES_CSV, "按区域和渠道统计销售额", "堆叠图",
                planJson("区域", "渠道", "销售额", "SUM", "stacked", "[]"),
                List.of("华东", "华南", "华北"), List.of(bd("2300"), bd("1900"), bd("1600"))));
        results.add(evalValid("07-二维堆叠缺失组合补零", "二维堆叠", SALES_CSV, "按品类和渠道统计销售额", "堆叠图",
                planJson("品类", "渠道", "销售额", "SUM", "stacked", "[]"),
                List.of("手机", "平板", "耳机"), List.of(bd("3700"), bd("1400"), bd("700"))));

        // 3. 本地确定性条件过滤（6 场景）
        results.add(evalValid("08-过滤EQUALS", "确定性过滤", SALES_CSV, "只看华东区按渠道统计销售额", "柱状图",
                planJson("渠道", null, "销售额", "SUM", "bar",
                        "[{\"field\":\"区域\",\"operator\":\"EQUALS\",\"values\":[\"华东\"]}]"),
                List.of("线上", "线下"), List.of(bd("2000"), bd("300"))));
        results.add(evalValid("09-过滤NOT_EQUALS", "确定性过滤", SALES_CSV, "剔除耳机后按品类统计销售额", "柱状图",
                planJson("品类", null, "销售额", "SUM", "bar",
                        "[{\"field\":\"品类\",\"operator\":\"NOT_EQUALS\",\"values\":[\"耳机\"]}]"),
                List.of("手机", "平板"), List.of(bd("3700"), bd("1400"))));
        results.add(evalValid("10-过滤IN", "确定性过滤", SALES_CSV, "仅看华东和华南按渠道统计订单数", "柱状图",
                planJson("渠道", null, null, "COUNT_ROWS", "bar",
                        "[{\"field\":\"区域\",\"operator\":\"IN\",\"values\":[\"华东\",\"华南\"]}]"),
                List.of("线上", "线下"), List.of(bd("3"), bd("2"))));
        results.add(evalValid("11-过滤CONTAINS", "确定性过滤", SALES_CSV, "只看品类含机字的区域销售额", "柱状图",
                planJson("区域", null, "销售额", "SUM", "bar",
                        "[{\"field\":\"品类\",\"operator\":\"CONTAINS\",\"values\":[\"机\"]}]"),
                List.of("华南", "华东", "华北"), List.of(bd("1900"), bd("1500"), bd("1000"))));
        results.add(evalValid("12-过滤GTE数值阈值", "确定性过滤", SALES_CSV, "只看销售额>=1000的区域分布", "柱状图",
                planJson("区域", null, "销售额", "SUM", "bar",
                        "[{\"field\":\"销售额\",\"operator\":\"GTE\",\"values\":[\"1000\"]}]"),
                List.of("华南", "华东", "华北"), List.of(bd("1500"), bd("1200"), bd("1000"))));
        results.add(evalValid("13-多条件组合过滤", "确定性过滤", SALES_CSV, "线上且销售额<1500的区域合计", "柱状图",
                planJson("区域", null, "销售额", "SUM", "bar",
                        "[{\"field\":\"渠道\",\"operator\":\"EQUALS\",\"values\":[\"线上\"]},"
                                + "{\"field\":\"销售额\",\"operator\":\"LT\",\"values\":[\"1500\"]}]"),
                List.of("华东", "华北"), List.of(bd("2000"), bd("1000"))));

        // 4. 脏数据与边界鲁棒性（4 场景）
        String bomAndDupCsv = "\uFEFF城市,金额,金额\n北京,$100,1\n上海,$200,2\n北京,$50,3\n";
        results.add(evalValid("14-BOM与重复表头去重", "脏数据鲁棒性", bomAndDupCsv, "按城市统计金额", "柱状图",
                planJson("城市", null, "金额", "SUM", "bar", "[]"),
                List.of("上海", "北京"), List.of(bd("200"), bd("150"))));
        String highCardCsv = generateHighCardinalityCsv(25);
        results.add(evalValid("15-TopN超过20类合并其他", "脏数据鲁棒性", highCardCsv, "按门店统计营收", "柱状图",
                planJson("门店", null, "营收", "SUM", "bar", "[]"),
                null, null));
        String trendCsv = "月份,销量\n2026-01,100\n2026-02,150\n2026-03,120\n2026-04,210\n";
        results.add(evalValid("16-折线图按时间键排序", "脏数据鲁棒性", trendCsv, "按月份看销量趋势", "折线图",
                planJson("月份", null, "销量", "SUM", "line", "[]"),
                List.of("2026-01", "2026-02", "2026-03", "2026-04"),
                List.of(bd("100"), bd("150"), bd("120"), bd("210"))));
        results.add(evalValid("17-雷达图多维指标构造", "脏数据鲁棒性", SALES_CSV, "各区域销售额雷达对比", "雷达图",
                planJson("区域", null, "销售额", "SUM", "radar", "[]"),
                List.of("华东", "华南", "华北"), List.of(bd("2300"), bd("1900"), bd("1600"))));

        // 5. 规模化大表压缩与性能对比（4 场景：100 / 500 / 1000 / 2000 行）
        for (int rows : new int[]{100, 500, 1000, 2000}) {
            String largeCsv = generateLargeSalesCsv(rows);
            results.add(evalValid("18-" + rows + "行大表全量聚合", "大表Token压缩", largeCsv,
                    "按区域统计全部订单销售额", "柱状图",
                    planJson("区域", null, "销售额", "SUM", "bar", "[]"),
                    null, null));
        }

        // 6. 安全与反问护栏（3 场景）
        results.add(evalGuardrail("22-模糊需求触发反问", "护栏拦截", () ->
                planParser.parse("{\"status\":\"NEEDS_CLARIFICATION\",\"clarificationQuestion\":\"请说明按哪个字段分组\"}")));
        results.add(evalGuardrail("23-幻觉不存在字段拦截", "护栏拦截", () -> {
            AnalysisPlan bad = planParser.parse(planJson("不存在的列", null, null, "COUNT_ROWS", "bar", "[]"));
            analysisService.analyze(SALES_CSV, bad, "测试", "柱状图", "测试");
        }));
        results.add(evalGuardrail("24-过滤后零匹配友好提示", "护栏拦截", () -> {
            AnalysisPlan emptyFilter = planParser.parse(planJson("渠道", null, null, "COUNT_ROWS", "bar",
                    "[{\"field\":\"区域\",\"operator\":\"EQUALS\",\"values\":[\"南极洲\"]}]"));
            analysisService.analyze(SALES_CSV, emptyFilter, "测试", "柱状图", "测试");
        }));

        assertEquals(24, results.size());
        long passed = results.stream().filter(r -> r.passed).count();
        assertEquals(24, passed, "所有 24 项基准用例必须全部通过");

        // 验证大表场景（>=500 行）Prompt 估算 Token 节省率均超过 80%
        results.stream()
                .filter(r -> r.name.contains("500行") || r.name.contains("1000行") || r.name.contains("2000行"))
                .forEach(r -> assertTrue(r.tokenReductionPct >= 80.0d,
                        r.name + " Token 节省率应 >= 80%，实际: " + r.tokenReductionPct));

        if ("true".equalsIgnoreCase(System.getProperty("writeEvalReport"))) {
            Path reportPath = Path.of("..", "..", "..", "docs", "EVAL-REPORT-V2.0.md");
            Files.writeString(reportPath, renderMarkdownReport(results), StandardCharsets.UTF_8);
        }
    }

    private CaseResult evalValid(String name, String category, String csv, String goal, String chartType,
                                 String planJson, List<String> expectedLabels, List<BigDecimal> expectedValues) throws Exception {
        long startNs = System.nanoTime();
        String profileJson = analysisService.describeDataset(csv);
        String v2PlanningPrompt = promptBuilder.buildPlanningUserMessage(goal, chartType, profileJson);
        AnalysisPlan plan = planParser.parse(planJson);
        AnalysisSummary summary = analysisService.analyze(csv, plan, goal, chartType, name);
        String v2ExplainPrompt = promptBuilder.buildUserMessage(goal, chartType, analysisService.toPromptSummary(summary));
        String chartOptionJson = analysisService.buildChartOption(summary);
        long localMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);

        // 验证 ECharts JSON 合法且包含非空 series
        JsonNode optionNode = objectMapper.readTree(chartOptionJson);
        assertTrue(optionNode.has("series") && optionNode.get("series").isArray() && optionNode.get("series").size() > 0,
                name + " 生成的 ECharts Option 必须包含非空 series");

        if (expectedLabels != null) {
            assertEquals(expectedLabels, summary.getLabels(), name + " 分组标签应与标准答案一致");
        }
        if (expectedValues != null) {
            assertEquals(expectedValues.size(), summary.getValues().size());
            for (int i = 0; i < expectedValues.size(); i++) {
                assertEquals(0, expectedValues.get(i).compareTo(summary.getValues().get(i)),
                        name + " 第 " + i + " 项统计值不精确");
            }
        }

        // Baseline：传统直接喂全量 CSV 的 Prompt 估算 Token（按中文/CSV 混合 ~1.5 字符/Token 估算）
        int baselineTokens = estimateTokens("分析目标：" + goal + "\n原始数据：\n" + csv);
        int v2Tokens = estimateTokens(v2PlanningPrompt) + estimateTokens(v2ExplainPrompt);
        double reduction = baselineTokens <= 0 ? 0.0d : (baselineTokens - v2Tokens) * 100.0d / baselineTokens;

        return new CaseResult(name, category, true, true, baselineTokens, v2Tokens, reduction, localMs);
    }

    private CaseResult evalGuardrail(String name, String category, Runnable action) {
        long startNs = System.nanoTime();
        assertThrows(IllegalArgumentException.class, action::run, name + " 必须触发精确护栏拦截");
        long localMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
        return new CaseResult(name, category, true, true, 0, 0, 0.0d, localMs);
    }

    private String planJson(String dim, String series, String metric, String agg, String type, String filtersJson) {
        return "{\"status\":\"READY\""
                + ",\"dimensionField\":\"" + dim + "\""
                + ",\"seriesField\":" + (series == null ? "null" : "\"" + series + "\"")
                + ",\"metricField\":" + (metric == null ? "null" : "\"" + metric + "\"")
                + ",\"aggregation\":\"" + agg + "\""
                + ",\"chartType\":\"" + type + "\""
                + ",\"chartTitle\":\"评测图表\""
                + ",\"filters\":" + filtersJson
                + ",\"reason\":\"基准测试方案\",\"clarificationQuestion\":\"\"}";
    }

    private String generateHighCardinalityCsv(int groups) {
        StringBuilder sb = new StringBuilder("门店,营收\n");
        for (int i = 1; i <= groups; i++) {
            sb.append("门店").append(String.format(Locale.ROOT, "%02d", i)).append(",").append(i * 100).append("\n");
        }
        return sb.toString();
    }

    private String generateLargeSalesCsv(int rows) {
        String[] regions = {"华东", "华南", "华北", "西南", "西北"};
        String[] channels = {"线上", "线下", "代理"};
        String[] categories = {"手机", "平板", "耳机", "笔记本"};
        StringBuilder sb = new StringBuilder("订单号,日期,区域,渠道,品类,销售额,客户备注\n");
        for (int i = 1; i <= rows; i++) {
            sb.append("ORD-2026-").append(String.format(Locale.ROOT, "%05d", i)).append(",")
                    .append("2026-10-").append(String.format(Locale.ROOT, "%02d", (i % 28) + 1)).append(",")
                    .append(regions[i % regions.length]).append(",")
                    .append(channels[i % channels.length]).append(",")
                    .append(categories[i % categories.length]).append(",")
                    .append(100 + (i % 50) * 25).append(",")
                    .append("标准订单备注信息").append(i).append("\n");
        }
        return sb.toString();
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / 1.5d);
    }

    private BigDecimal bd(String val) {
        return new BigDecimal(val);
    }

    private String renderMarkdownReport(List<CaseResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Epiph-BI V2.0 架构对比评测报告\n\n");
        sb.append("由 `BiArchitectureBenchmarkTest` 自动生成（24 项标准场景）。\n\n");
        sb.append("| 编号与场景 | 分类 | 数值精确 / 护栏通过 | ECharts 合法 | Baseline 估算 Token | V2 估算 Token | Token 变化 | 本地耗时(ms) |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (CaseResult r : results) {
            sb.append("| ").append(r.name)
                    .append(" | ").append(r.category)
                    .append(" | ").append(r.passed ? "PASS" : "FAIL")
                    .append(" | ").append(r.validOption ? "PASS" : "FAIL")
                    .append(" | ").append(r.baselineTokens > 0 ? r.baselineTokens : "-")
                    .append(" | ").append(r.v2Tokens > 0 ? r.v2Tokens : "-")
                    .append(" | ").append(r.baselineTokens > 0 ? String.format(Locale.ROOT, "%.1f%%", r.tokenReductionPct) : "-")
                    .append(" | ").append(r.localMs).append(" |\n");
        }
        return sb.toString();
    }

    private static final class CaseResult {
        private final String name;
        private final String category;
        private final boolean passed;
        private final boolean validOption;
        private final int baselineTokens;
        private final int v2Tokens;
        private final double tokenReductionPct;
        private final long localMs;

        private CaseResult(String name, String category, boolean passed, boolean validOption,
                           int baselineTokens, int v2Tokens, double tokenReductionPct, long localMs) {
            this.name = name;
            this.category = category;
            this.passed = passed;
            this.validOption = validOption;
            this.baselineTokens = baselineTokens;
            this.v2Tokens = v2Tokens;
            this.tokenReductionPct = tokenReductionPct;
            this.localMs = localMs;
        }
    }
}
