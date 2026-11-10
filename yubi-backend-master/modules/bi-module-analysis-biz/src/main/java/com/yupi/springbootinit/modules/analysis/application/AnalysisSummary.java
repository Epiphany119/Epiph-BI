package com.yupi.springbootinit.modules.analysis.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 可持久化的本地统计快照。任务重试时直接复用该快照，不必再次上传文件，
 * AI 也不会收到整份原始表格。
 */
public class AnalysisSummary {
    private int schemaVersion = 1;
    private int sourceRowCount;
    private int groupCount;
    private String dimensionField;
    private String metricField;
    private String aggregation;
    private String seriesName;
    private String chartType;
    private String chartTitle;
    private String note;
    private String planReason;
    private List<String> labels = new ArrayList<>();
    private List<BigDecimal> values = new ArrayList<>();

    public AnalysisSummary() {
    }

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public int getSourceRowCount() { return sourceRowCount; }
    public void setSourceRowCount(int sourceRowCount) { this.sourceRowCount = sourceRowCount; }
    public int getGroupCount() { return groupCount; }
    public void setGroupCount(int groupCount) { this.groupCount = groupCount; }
    public String getDimensionField() { return dimensionField; }
    public void setDimensionField(String dimensionField) { this.dimensionField = dimensionField; }
    public String getMetricField() { return metricField; }
    public void setMetricField(String metricField) { this.metricField = metricField; }
    public String getAggregation() { return aggregation; }
    public void setAggregation(String aggregation) { this.aggregation = aggregation; }
    public String getSeriesName() { return seriesName; }
    public void setSeriesName(String seriesName) { this.seriesName = seriesName; }
    public String getChartType() { return chartType; }
    public void setChartType(String chartType) { this.chartType = chartType; }
    public String getChartTitle() { return chartTitle; }
    public void setChartTitle(String chartTitle) { this.chartTitle = chartTitle; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getPlanReason() { return planReason; }
    public void setPlanReason(String planReason) { this.planReason = planReason; }
    public List<String> getLabels() { return labels; }
    public void setLabels(List<String> labels) { this.labels = labels; }
    public List<BigDecimal> getValues() { return values; }
    public void setValues(List<BigDecimal> values) { this.values = values; }
}
