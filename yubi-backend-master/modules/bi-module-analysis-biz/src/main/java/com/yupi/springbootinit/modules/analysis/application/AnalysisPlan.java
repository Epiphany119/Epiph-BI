package com.yupi.springbootinit.modules.analysis.application;

/** 由配置的 AI 模型根据分析目标和表格画像生成，并由服务端校验后执行的分析方案。 */
public class AnalysisPlan {
    private String dimensionField;
    private String seriesField;
    private String metricField;
    private String aggregation;
    private String chartType;
    private String chartTitle;
    private String reason;

    public String getDimensionField() { return dimensionField; }
    public void setDimensionField(String dimensionField) { this.dimensionField = dimensionField; }
    public String getSeriesField() { return seriesField; }
    public void setSeriesField(String seriesField) { this.seriesField = seriesField; }
    public String getMetricField() { return metricField; }
    public void setMetricField(String metricField) { this.metricField = metricField; }
    public String getAggregation() { return aggregation; }
    public void setAggregation(String aggregation) { this.aggregation = aggregation; }
    public String getChartType() { return chartType; }
    public void setChartType(String chartType) { this.chartType = chartType; }
    public String getChartTitle() { return chartTitle; }
    public void setChartTitle(String chartTitle) { this.chartTitle = chartTitle; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
