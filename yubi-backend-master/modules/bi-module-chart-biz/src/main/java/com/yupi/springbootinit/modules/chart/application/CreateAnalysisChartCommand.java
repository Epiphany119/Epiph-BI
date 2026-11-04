package com.yupi.springbootinit.modules.chart.application;

/**
 * 创建分析图表的跨模块命令；用户 ID 必须来自服务端认证上下文。
 */
public final class CreateAnalysisChartCommand {
    private final String name;
    private final String goal;
    private final String chartType;
    private final String chartData;
    private final Long userId;

    public CreateAnalysisChartCommand(String name, String goal, String chartType, String chartData, Long userId) {
        this.name = name;
        this.goal = goal;
        this.chartType = chartType;
        this.chartData = chartData;
        this.userId = userId;
    }

    public String getName() { return name; }
    public String getGoal() { return goal; }
    public String getChartType() { return chartType; }
    public String getChartData() { return chartData; }
    public Long getUserId() { return userId; }
}
