package com.yupi.springbootinit.modules.chart.application;

import com.yupi.springbootinit.model.vo.AiUsageSummaryVO;

import java.util.List;

/**
 * analysis-biz 使用的图表任务受控 API；不提供通用实体更新或 Mapper 能力。
 */
public interface ChartApplicationService {
    Long createAnalysisTask(CreateAnalysisChartCommand command);

    AnalysisChartContext getAnalysisContext(Long chartId);

    boolean tryStartAnalysis(Long chartId, String workerId, int leaseSeconds);

    boolean completeAnalysis(Long chartId, String workerId, String genChart, String genResult);

    boolean failAnalysis(Long chartId, String workerId, String execMessage);

    /** 查询有界数量的待执行任务，供应用层恢复器补偿投递。 */
    List<Long> findWaitingAnalysisTaskIds(int limit);

    /** 回收租约已过期的 RUNNING 任务：未达上限回退 WAITING，达上限原子置为 FAILED。 */
    ReclaimResult reclaimExpiredRunningTasks(int limit);

    AnalysisChartContext prepareRetry(Long chartId, Long userId);

    /** 记录单次 AI 调用用量日志，并在关联 chartId 时累加到图表汇总指标。 */
    void recordAiUsage(RecordAiUsageCommand command);

    /** 查询指定用户的 AI 调用用量、估算费用、降级次数与近 7 天趋势。 */
    AiUsageSummaryVO getUserUsageSummary(Long userId);
}
