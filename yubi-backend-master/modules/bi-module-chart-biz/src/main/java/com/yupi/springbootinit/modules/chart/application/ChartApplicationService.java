package com.yupi.springbootinit.modules.chart.application;

/**
 * analysis-biz 使用的图表任务受控 API；不提供通用实体更新或 Mapper 能力。
 */
public interface ChartApplicationService {
    Long createAnalysisTask(CreateAnalysisChartCommand command);

    AnalysisChartContext getAnalysisContext(Long chartId);

    boolean tryStartAnalysis(Long chartId);

    boolean completeAnalysis(Long chartId, String genChart, String genResult);

    boolean failAnalysis(Long chartId, String execMessage);

    AnalysisChartContext prepareRetry(Long chartId, Long userId);
}
