package com.yupi.springbootinit.modules.analysis.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 在本地对表格做精确的分组、计数、求和或平均值计算。
 * 向规划模型发送有限的表格画像和样例；图表数值由本地全量聚合结果生成。
 */
@Component
public class SpreadsheetAnalysisService {
    private static final int MAX_CHART_GROUPS = 20;
    private static final int MAX_LABEL_LENGTH = 80;
    private static final String COUNT = "COUNT";
    private static final String COUNT_DISTINCT = "COUNT_DISTINCT";
    private static final String SUM = "SUM";
    private static final String AVERAGE = "AVERAGE";

    private static final String[] SUM_WORDS = {
            "合计", "求和", "总和", "总额", "销售额", "金额", "营收", "收入", "利润", "交易额", "营业额"
    };
    private static final String[] AVERAGE_WORDS = {"平均", "均值", "均价", "平均值", "average", "avg"};
    private final ObjectMapper objectMapper;

    public SpreadsheetAnalysisService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 兼容旧版以原始 CSV 持久化的任务；新任务必须使用模型方案重载。 */
    @Deprecated
    public AnalysisSummary analyze(String csvData, String goal, String requestedChartType, String chartName) {
        if (csvData == null || csvData.isBlank()) throw new IllegalArgumentException("表格中没有可分析的数据");
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("分析目标不能为空");
        ParsedTable table = parseTable(csvData);
        List<ColumnProfile> profiles = profile(table.headers, table.rows);
        ColumnProfile dimension = chooseDimension(profiles, goal, table.rows.size());
        if (dimension == null) {
            throw new IllegalArgumentException("无法识别可分组字段，请在分析目标中写明要按哪一列表头分类");
        }

        String aggregation = chooseAggregation(goal);
        ColumnProfile measure = aggregation.equals(COUNT) ? null : chooseMeasure(profiles, goal);
        if (!aggregation.equals(COUNT) && measure == null) {
            throw new IllegalArgumentException("分析目标要求数值统计，但表格中没有可用的数值列；请在目标中注明数值列名");
        }

        return aggregate(table, dimension, measure, aggregation, resolveChartType(requestedChartType), safeLabel(goal), null);
    }

    /** 模型先根据目标和表格画像选择字段与口径；服务端校验方案，再对全量行精确计算。 */
    public AnalysisSummary analyze(String csvData, AnalysisPlan plan, String goal,
                                   String requestedChartType, String chartName) {
        if (csvData == null || csvData.isBlank()) throw new IllegalArgumentException("表格中没有可分析的数据");
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("分析目标不能为空");
        if (plan == null) throw new IllegalArgumentException("模型没有返回分析方案");

        ParsedTable table = parseTable(csvData);
        List<ColumnProfile> profiles = profile(table.headers, table.rows);
        ColumnProfile dimension = exactColumn(profiles, plan.getDimensionField(), "分组");
        if (dimension.nonEmpty == 0 || dimension.distinctValues.size() < 2) {
            throw new IllegalArgumentException("模型选择的分组字段“" + dimension.name + "”没有足够的不同值");
        }

        String aggregation = COUNT.equals(plan.getAggregation()) ? COUNT : plan.getAggregation();
        if ("COUNT_ROWS".equals(aggregation)) aggregation = COUNT;
        if (!List.of(COUNT, COUNT_DISTINCT, SUM, AVERAGE).contains(aggregation)) {
            throw new IllegalArgumentException("模型返回了不支持的统计方式");
        }
        ColumnProfile measure = null;
        if (!COUNT.equals(aggregation)) {
            measure = exactColumn(profiles, plan.getMetricField(), "统计");
            if (measure == dimension) throw new IllegalArgumentException("分组字段和统计字段不能相同");
            if ((SUM.equals(aggregation) || AVERAGE.equals(aggregation)) && !measure.isMostlyNumeric()) {
                throw new IllegalArgumentException("模型选择的统计字段“" + measure.name + "”不是可用数值列");
            }
        }

        String chartType = requestedChartType == null || requestedChartType.isBlank()
                ? normalizePlannedChartType(plan.getChartType()) : resolveChartType(requestedChartType);
        String chartTitle = plan.getChartTitle() == null || plan.getChartTitle().isBlank()
                ? safeLabel(goal) : safeLabel(plan.getChartTitle());
        return aggregate(table, dimension, measure, aggregation, chartType, chartTitle, plan.getReason());
    }

    /** 为模型规划提供完整列画像、示例值和少量代表行；原始全量数据仍用于本地精确聚合。 */
    public String describeDataset(String csvData) {
        ParsedTable table = parseTable(csvData);
        List<ColumnProfile> profiles = profile(table.headers, table.rows);
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("rowCount", table.rows.size());
        List<Map<String, Object>> fields = new ArrayList<>();
        for (ColumnProfile profile : profiles) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", profile.name);
            field.put("inferredType", profile.isMostlyNumeric() ? "number" : "text_or_category");
            field.put("nonEmptyCount", profile.nonEmpty);
            field.put("distinctCount", profile.distinctValues.size());
            field.put("examples", profile.exampleValues());
            fields.add(field);
        }
        description.put("fields", fields);
        List<Map<String, String>> samples = new ArrayList<>();
        for (List<String> row : representativeRows(table.rows, 8)) {
            Map<String, String> sample = new LinkedHashMap<>();
            for (int i = 0; i < table.headers.size(); i++) sample.put(table.headers.get(i), row.get(i));
            samples.add(sample);
        }
        description.put("sampleRows", samples);
        try {
            return objectMapper.writeValueAsString(description);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("表格画像序列化失败", e);
        }
    }

    private static List<List<String>> representativeRows(List<List<String>> rows, int limit) {
        return representativeItems(rows, limit);
    }

    private static <T> List<T> representativeItems(List<T> items, int limit) {
        int count = Math.min(items.size(), limit);
        List<T> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int index = count == 1 ? 0 : (int) ((long) i * (items.size() - 1) / (count - 1));
            samples.add(items.get(index));
        }
        return samples;
    }

    private ParsedTable parseTable(String csvData) {
        if (csvData == null || csvData.isBlank()) throw new IllegalArgumentException("表格中没有可分析的数据");
        List<String> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(csvData))) {
            java.util.Iterator<CSVRecord> iterator = parser.iterator();
            if (!iterator.hasNext()) throw new IllegalArgumentException("表格中没有可分析的数据");
            CSVRecord headerRecord = iterator.next();
            for (int i = 0; i < headerRecord.size(); i++) {
                String header = normalizeHeader(headerRecord.get(i), i);
                headers.add(makeUniqueHeader(headers, header));
            }
            while (iterator.hasNext()) {
                CSVRecord record = iterator.next();
                if (record.size() > headers.size()) throw new IllegalArgumentException("表格数据列数与表头不一致");
                List<String> row = new ArrayList<>(headers.size());
                for (int i = 0; i < headers.size(); i++) row.add(i < record.size() ? record.get(i).trim() : "");
                rows.add(row);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("表格统计失败", e);
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("表格中没有数据行");
        return new ParsedTable(headers, rows);
    }

    private ColumnProfile exactColumn(List<ColumnProfile> profiles, String name, String kind) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("模型没有指定" + kind + "字段");
        return profiles.stream().filter(profile -> profile.name.equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("模型选择的" + kind + "字段“" + name + "”不在表格表头中"));
    }

    private String normalizePlannedChartType(String chartType) {
        return chartType != null && List.of("bar", "line", "pie", "radar").contains(chartType.toLowerCase(Locale.ROOT))
                ? chartType.toLowerCase(Locale.ROOT) : "bar";
    }

    private AnalysisSummary aggregate(ParsedTable table, ColumnProfile dimension, ColumnProfile measure,
                                      String aggregation, String chartType, String chartTitle, String planReason) {
        Map<String, GroupValue> groups = new HashMap<>();
        int skippedMeasureValues = 0;
        for (List<String> row : table.rows) {
            String rawLabel = row.get(dimension.index).trim();
            String label = rawLabel.isEmpty() ? "未填写" : rawLabel;
            GroupValue group = groups.computeIfAbsent(label, ignored -> new GroupValue());
            group.rowCount++;
            if (COUNT_DISTINCT.equals(aggregation)) {
                String value = row.get(measure.index).trim();
                if (!value.isEmpty()) group.distinctMeasureValues.add(value);
            } else if (measure != null) {
                BigDecimal value = parseNumber(row.get(measure.index));
                if (value == null) skippedMeasureValues++;
                else {
                    group.measureSum = group.measureSum.add(value);
                    group.measureCount++;
                }
            }
        }

        List<Map.Entry<String, GroupValue>> sorted = new ArrayList<>(groups.entrySet());
        if (chartType.equals("line")) sorted.sort(Map.Entry.comparingByKey());
        else sorted.sort(Comparator.comparing((Map.Entry<String, GroupValue> entry) ->
                groupResult(entry.getValue(), aggregation)).reversed());
        AnalysisSummary summary = new AnalysisSummary();
        summary.setSourceRowCount(table.rows.size());
        summary.setGroupCount(groups.size());
        summary.setDimensionField(dimension.name);
        summary.setMetricField(measure == null ? null : measure.name);
        summary.setAggregation(aggregation);
        summary.setSeriesName(seriesName(aggregation, measure));
        summary.setChartType(chartType);
        summary.setChartTitle(chartTitle);
        summary.setPlanReason(planReason);

        StringBuilder note = new StringBuilder("图表中的统计值由服务器对全部 ")
                .append(table.rows.size()).append(" 条数据记录本地计算，分组字段为“")
                .append(dimension.name).append("”，统计方式为“")
                .append(aggregationName(aggregation)).append("”。");
        if (planReason != null && !planReason.isBlank()) note.append("字段与统计口径由模型根据目标和表格画像选择：").append(planReason).append("。");
        boolean lineChart = summary.getChartType().equals("line");
        int visibleCount = Math.min(sorted.size(), lineChart ? MAX_CHART_GROUPS
                : (sorted.size() > MAX_CHART_GROUPS ? MAX_CHART_GROUPS - 1 : MAX_CHART_GROUPS));
        for (int i = 0; i < visibleCount; i++) {
            Map.Entry<String, GroupValue> entry = sorted.get(i);
            summary.getLabels().add(safeLabel(entry.getKey()));
            summary.getValues().add(groupResult(entry.getValue(), aggregation));
        }
        if (sorted.size() > MAX_CHART_GROUPS && lineChart) {
            note.append("折线图按类别顺序只展示前 ").append(MAX_CHART_GROUPS)
                    .append(" 组；更早/其余类别未绘制，摘要仍基于全部数据行。");
        } else if (sorted.size() > MAX_CHART_GROUPS) {
            GroupValue other = new GroupValue();
            int hiddenGroups = 0;
            for (int i = visibleCount; i < sorted.size(); i++) {
                GroupValue hidden = sorted.get(i).getValue();
                other.rowCount += hidden.rowCount;
                other.measureSum = other.measureSum.add(hidden.measureSum);
                other.measureCount += hidden.measureCount;
                other.distinctMeasureValues.addAll(hidden.distinctMeasureValues);
                hiddenGroups++;
            }
            summary.getLabels().add("其他（" + hiddenGroups + " 类）");
            summary.getValues().add(groupResult(other, aggregation));
            note.append("类别较多，图表展示前 ").append(visibleCount)
                    .append(" 类，其余类别合并为“其他”，合计仍包含全部数据。");
        }
        if (skippedMeasureValues > 0) note.append("有 ").append(skippedMeasureValues).append(" 个空值或非数值单元格未计入数值统计。");
        summary.setNote(note.toString());
        return summary;
    }

    /** 任务重试优先复用统计快照，并兼容已经落库的旧版原始 CSV。 */
    public AnalysisSummary restore(String chartData, String goal, String chartType, String chartName) {
        if (chartData == null || chartData.isBlank()) throw new IllegalArgumentException("任务缺少数据摘要");
        try {
            AnalysisSummary summary = objectMapper.readValue(chartData, AnalysisSummary.class);
            if (summary.getSchemaVersion() == 1 && summary.getLabels() != null
                    && summary.getValues() != null && summary.getLabels().size() == summary.getValues().size()
                    && !summary.getLabels().isEmpty()) {
                return summary;
            }
        } catch (Exception ignored) {
            // 旧任务的 chartData 保存的是原始 CSV；下面按同一规则重新生成摘要。
        }
        return analyze(chartData, goal, chartType, chartName);
    }

    /** 返回发送给 AI 的有限统计摘要，不包含原始行数据。 */
    public String toPromptSummary(AnalysisSummary summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("统计摘要序列化失败", e);
        }
    }

    /** 持久化快照格式与 Prompt 摘要共用，确保失败任务可复用同一组全量统计。 */
    public String serialize(AnalysisSummary summary) {
        return toPromptSummary(summary);
    }

    /** 使用本地聚合值生成 ECharts option，避免模型生成示例或虚构数值。 */
    public String buildChartOption(AnalysisSummary summary) {
        if (summary == null || summary.getLabels() == null || summary.getValues() == null
                || summary.getLabels().isEmpty() || summary.getLabels().size() != summary.getValues().size()) {
            throw new IllegalArgumentException("本地统计结果不完整，无法生成图表");
        }
        String type = summary.getChartType();
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", Map.of("text", summary.getChartTitle()));
        option.put("tooltip", Map.of("trigger", type.equals("pie") ? "item" : "axis"));

        List<Object> series = new ArrayList<>();
        if (type.equals("pie")) {
            option.put("legend", Map.of("type", "scroll"));
            List<Map<String, Object>> data = new ArrayList<>();
            for (int i = 0; i < summary.getLabels().size(); i++) {
                data.add(Map.of("name", summary.getLabels().get(i), "value", summary.getValues().get(i)));
            }
            series.add(Map.of("name", summary.getSeriesName(), "type", "pie", "radius", "55%", "data", data));
        } else if (type.equals("radar")) {
            List<Map<String, Object>> indicators = new ArrayList<>();
            BigDecimal maximum = summary.getValues().stream().max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
            BigDecimal radarMax = maximum.signum() <= 0 ? BigDecimal.ONE : maximum.multiply(new BigDecimal("1.2"));
            for (String label : summary.getLabels()) indicators.add(Map.of("name", label, "max", radarMax));
            option.put("radar", Map.of("indicator", indicators));
            series.add(Map.of("name", summary.getSeriesName(), "type", "radar",
                    "data", List.of(Map.of("name", summary.getSeriesName(), "value", summary.getValues()))));
        } else {
            option.put("grid", Map.of("containLabel", true));
            option.put("xAxis", Map.of("type", "category", "data", summary.getLabels()));
            option.put("yAxis", Map.of("type", "value"));
            series.add(Map.of("name", summary.getSeriesName(), "type", type, "data", summary.getValues()));
        }
        option.put("series", series);
        try {
            return objectMapper.writeValueAsString(option);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("本地图表配置序列化失败", e);
        }
    }

    private List<ColumnProfile> profile(List<String> headers, List<List<String>> rows) {
        List<ColumnProfile> profiles = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) profiles.add(new ColumnProfile(i, headers.get(i)));
        for (List<String> row : rows) {
            for (ColumnProfile profile : profiles) {
                String value = row.get(profile.index).trim();
                if (value.isEmpty()) continue;
                profile.nonEmpty++;
                profile.distinctValues.add(value);
                BigDecimal number = parseNumber(value);
                if (number != null) profile.numericCount++;
            }
        }
        return profiles;
    }

    private ColumnProfile chooseDimension(List<ColumnProfile> profiles, String goal, int rowCount) {
        String normalizedGoal = normalize(goal);
        String[] requestedIntent = requestedDimensionIntent(normalizedGoal);
        if (requestedIntent == null) {
            // 用户直接写出表头时，优先使用该分类列。例如“销售管理情况”可匹配表头“销售”。
            ColumnProfile explicitHeader = chooseExplicitHeaderDimension(profiles, normalizedGoal, rowCount);
            if (explicitHeader != null) return explicitHeader;
            throw new IllegalArgumentException("分析目标没有明确分组字段，请写明按城市、平台、类型等哪一列表头分析");
        }
        ColumnProfile selected = null;
        int selectedScore = Integer.MIN_VALUE;
        for (ColumnProfile profile : profiles) {
            int distinctCount = profile.distinctValues.size();
            if (profile.nonEmpty == 0 || distinctCount < 2 || isIdentifier(profile.name)) continue;
            int score = 0;
            String normalizedHeader = normalize(profile.name);
            for (String token : requestedIntent) {
                String normalizedToken = normalize(token);
                if (normalizedHeader.contains(normalizedToken)) score += 100;
                if (normalizedGoal.contains(normalizedHeader)) score += 200;
            }
            if (score == 0) continue;
            score += Math.min(distinctCount, 50);
            if (profile.isMostlyNumeric()) score -= 20;
            if (distinctCount >= Math.max(2, rowCount * 0.9)) score -= 100;
            if (score > selectedScore) {
                selected = profile;
                selectedScore = score;
            }
        }
        if (selected == null) {
            String availableHeaders = profiles.stream().limit(12)
                    .map(profile -> safeLabel(profile.name)).collect(Collectors.joining("、"));
            if (profiles.size() > 12) availableHeaders += "等";
            throw new IllegalArgumentException("分析目标要求按“" + requestedIntent[0]
                    + "”分组，但表格中没有对应字段。当前表头：" + availableHeaders);
        }
        return selected;
    }

    /** 没有命中常见维度词时，允许目标直接引用表头，并且只从分类字段中选择。 */
    private ColumnProfile chooseExplicitHeaderDimension(List<ColumnProfile> profiles, String normalizedGoal, int rowCount) {
        boolean requestsSalesMetric = containsAny(normalizedGoal, "销售额", "销售量", "销售金额", "销售收入", "销售营收");
        ColumnProfile selected = null;
        int selectedScore = Integer.MIN_VALUE;
        for (ColumnProfile profile : profiles) {
            int distinctCount = profile.distinctValues.size();
            if (profile.nonEmpty == 0 || distinctCount < 2 || isIdentifier(profile.name)
                    || profile.isMostlyNumeric()) continue;
            String normalizedHeader = normalize(profile.name);
            if (normalizedHeader.length() < 2 || !normalizedGoal.contains(normalizedHeader)) continue;
            // “销售额/销售量”等是数值指标意图，不能误把“销售”人员列当作分组维度。
            if (requestsSalesMetric && normalizedHeader.equals("销售")) continue;

            int score = normalizedHeader.length() * 100 + Math.min(distinctCount, 50);
            if (distinctCount >= Math.max(2, rowCount * 0.9)) score -= 100;
            if (score > selectedScore) {
                selected = profile;
                selectedScore = score;
            }
        }
        return selected;
    }

    /** 只根据用户明确写出的维度选择字段，防止把品牌、商圈等猜成用户要求的平台。 */
    private String[] requestedDimensionIntent(String normalizedGoal) {
        String[][] intents = {
                {"平台", "渠道", "来源"},
                {"地点", "位置", "地区", "区域", "城市", "省份", "行政区", "区县", "商圈", "地址"},
                {"品牌", "产品", "商品"},
                {"类型", "分类", "类别", "行业"},
                {"时间", "日期", "月份", "季度", "年份", "按月", "按年", "每日", "每月", "趋势"},
                {"员工", "负责人", "顾问", "销售人员"}
        };
        String[] selected = null;
        for (String[] intent : intents) {
            for (String token : intent) {
                if (!normalizedGoal.contains(normalize(token))) continue;
                if (selected != null && selected != intent) {
                    throw new IllegalArgumentException("分析目标同时提到多个分组维度，请明确只按一个字段分组");
                }
                selected = intent;
                break;
            }
        }
        return selected;
    }

    private String chooseAggregation(String goal) {
        String normalized = normalize(goal);
        if (containsAny(normalized, AVERAGE_WORDS)) return AVERAGE;
        if (containsAny(normalized, SUM_WORDS)) return SUM;
        return COUNT;
    }

    private ColumnProfile chooseMeasure(List<ColumnProfile> profiles, String goal) {
        String normalizedGoal = normalize(goal);
        ColumnProfile selected = null;
        int selectedScore = Integer.MIN_VALUE;
        for (ColumnProfile profile : profiles) {
            if (!profile.isMostlyNumeric() || profile.nonEmpty == 0) continue;
            int score = 0;
            if (normalizedGoal.contains(normalize(profile.name))) score += 1000 + Math.min(profile.name.length(), 30);
            if (isIdentifier(profile.name)) score -= 500;
            for (String token : SUM_WORDS) {
                if (normalizedGoal.contains(normalize(token)) && normalize(profile.name).contains(normalize(token))) score += 200;
            }
            if (score > selectedScore) {
                selected = profile;
                selectedScore = score;
            }
        }
        return selectedScore > 0 ? selected : null;
    }

    private BigDecimal groupResult(GroupValue group, String aggregation) {
        if (aggregation.equals(COUNT)) return BigDecimal.valueOf(group.rowCount);
        if (aggregation.equals(COUNT_DISTINCT)) return BigDecimal.valueOf(group.distinctMeasureValues.size());
        if (aggregation.equals(AVERAGE)) {
            return group.measureCount == 0 ? BigDecimal.ZERO
                    : group.measureSum.divide(BigDecimal.valueOf(group.measureCount), 4, RoundingMode.HALF_UP).stripTrailingZeros();
        }
        return group.measureSum.stripTrailingZeros();
    }

    private String seriesName(String aggregation, ColumnProfile measure) {
        if (aggregation.equals(COUNT)) return "记录数";
        if (aggregation.equals(COUNT_DISTINCT)) return measure.name + "去重数";
        return measure.name + (aggregation.equals(AVERAGE) ? "平均值" : "合计");
    }

    private String aggregationName(String aggregation) {
        if (aggregation.equals(COUNT)) return "按记录数计数";
        if (aggregation.equals(COUNT_DISTINCT)) return "去重计数";
        if (aggregation.equals(AVERAGE)) return "平均值";
        return "合计";
    }

    private String resolveChartType(String requested) {
        if (requested == null) return "bar";
        if (requested.contains("饼")) return "pie";
        if (requested.contains("雷达")) return "radar";
        if (requested.contains("折线")) return "line";
        return "bar";
    }

    private BigDecimal parseNumber(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().replace(",", "").replace("￥", "").replace("¥", "").replace("$", "");
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeHeader(String raw, int index) {
        String value = raw == null ? "" : raw.replace("\uFEFF", "").trim();
        return value.isEmpty() ? "列" + (index + 1) : value;
    }

    private String makeUniqueHeader(List<String> existing, String header) {
        if (!existing.contains(header)) return header;
        int suffix = 2;
        while (existing.contains(header + "_" + suffix)) suffix++;
        return header + "_" + suffix;
    }

    private String safeLabel(String raw) {
        String value = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        return value.length() > MAX_LABEL_LENGTH ? value.substring(0, MAX_LABEL_LENGTH) + "…" : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s_：:（）()\\-]", "");
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) if (value.contains(normalize(token))) return true;
        return false;
    }

    private boolean isIdentifier(String name) {
        String normalized = normalize(name);
        return containsAny(normalized, "id", "编号", "编码", "序号", "单号", "code", "serial");
    }

    private static final class ColumnProfile {
        private final int index;
        private final String name;
        private final Set<String> distinctValues = new LinkedHashSet<>();
        private int nonEmpty;
        private int numericCount;

        private ColumnProfile(int index, String name) {
            this.index = index;
            this.name = name;
        }

        private boolean isMostlyNumeric() {
            return nonEmpty > 0 && numericCount >= Math.ceil(nonEmpty * 0.8d);
        }

        private List<String> exampleValues() {
            List<String> values = new ArrayList<>(distinctValues);
            return representativeItems(values, 8);
        }
    }

    private static final class ParsedTable {
        private final List<String> headers;
        private final List<List<String>> rows;

        private ParsedTable(List<String> headers, List<List<String>> rows) {
            this.headers = headers;
            this.rows = rows;
        }
    }

    private static final class GroupValue {
        private long rowCount;
        private long measureCount;
        private BigDecimal measureSum = BigDecimal.ZERO;
        private final Set<String> distinctMeasureValues = new LinkedHashSet<>();
    }
}
