package com.yupi.springbootinit.modules.chart.application;

/**
 * 分析模块可读取的图表任务快照，不暴露 Chart 持久化实体。
 */
public final class AnalysisChartContext {
    private final Long chartId;
    private final Long userId;
    private final String name;
    private final String goal;
    private final String chartType;
    private final String chartData;
    private final String status;
    private final String genChart;
    private final String genResult;

    public AnalysisChartContext(Long chartId, Long userId, String name, String goal, String chartType,
                                String chartData, String status, String genChart, String genResult) {
        this.chartId = chartId;
        this.userId = userId;
        this.name = name;
        this.goal = goal;
        this.chartType = chartType;
        this.chartData = chartData;
        this.status = status;
        this.genChart = genChart;
        this.genResult = genResult;
    }

    public Long getChartId() { return chartId; }
    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public String getGoal() { return goal; }
    public String getChartType() { return chartType; }
    public String getChartData() { return chartData; }
    public String getStatus() { return status; }
    public String getGenChart() { return genChart; }
    public String getGenResult() { return genResult; }
}
