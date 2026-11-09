package com.yupi.springbootinit.modules.chart.application;

import java.util.List;

/**
 * analysis-biz 使用的图表任务受控 API；不提供通用实体更新或 Mapper 能力。
 */
public interface ChartApplicationService {
    Long createAnalysisTask(CreateAnalysisChartCommand command);

    AnalysisChartContext getAnalysisContext(Long chartId);

    boolean tryStartAnalysis(Long chartId);

    boolean completeAnalysis(Long chartId, String genChart, String genResult);

    boolean failAnalysis(Long chartId, String execMessage);

    /** 查询有界数量的待执行任务，供应用层恢复器补偿投递。 */
    List<Long> findWaitingAnalysisTaskIds(int limit);

    AnalysisChartContext prepareRetry(Long chartId, Long userId);
}
