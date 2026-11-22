package com.yupi.springbootinit.modules.analysis.job;

import com.yupi.springbootinit.modules.analysis.application.AnalysisTaskApplicationService;
import com.yupi.springbootinit.modules.chart.application.ReclaimResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/** 定期回收租约过期的 RUNNING 任务，并补偿未成功进入线程池或 RabbitMQ 的 WAITING 任务。 */
@Component
@Slf4j
public class WaitingAnalysisTaskRecoveryJob {

    private static final int BATCH_LIMIT = 20;

    private final AnalysisTaskApplicationService analysisTaskApplicationService;
    private final ThreadPoolExecutor threadPoolExecutor;

    public WaitingAnalysisTaskRecoveryJob(AnalysisTaskApplicationService analysisTaskApplicationService,
                                          ThreadPoolExecutor threadPoolExecutor) {
        this.analysisTaskApplicationService = analysisTaskApplicationService;
        this.threadPoolExecutor = threadPoolExecutor;
    }

    /** 先回收超时 RUNNING 租约（回退 WAITING 或达上限置 FAILED），再将 WAITING 任务重新提交到有界执行器。 */
    @Scheduled(fixedDelayString = "${analysis.task.recovery-interval-ms:30000}")
    public void recoverWaitingTasks() {
        try {
            ReclaimResult reclaimResult = analysisTaskApplicationService.reclaimExpiredRunningTasks(BATCH_LIMIT);
            if (reclaimResult != null && (reclaimResult.getRequeuedCount() > 0 || reclaimResult.getFailedCount() > 0)) {
                log.warn("已回收超时 RUNNING 分析任务，重新入队={} ({})，达重试上限置失败={} ({})",
                        reclaimResult.getRequeuedCount(), reclaimResult.getRequeuedChartIds(),
                        reclaimResult.getFailedCount(), reclaimResult.getFailedChartIds());
            }
        } catch (RuntimeException e) {
            log.error("回收超时 RUNNING 分析任务失败，继续执行 WAITING 任务扫描", e);
        }

        final List<Long> chartIds;
        try {
            chartIds = analysisTaskApplicationService.findWaitingTaskIds(BATCH_LIMIT);
        } catch (RuntimeException e) {
            log.error("查询 WAITING 分析任务失败，本轮恢复跳过", e);
            return;
        }

        for (Long chartId : chartIds) {
            try {
                threadPoolExecutor.execute(() -> {
                    try {
                        analysisTaskApplicationService.executeExistingTask(chartId);
                    } catch (RuntimeException e) {
                        log.error("恢复执行分析任务失败，chartId={}", chartId, e);
                    }
                });
            } catch (RejectedExecutionException e) {
                // 任务仍是 WAITING；等线程池空出位置后由下一轮扫描再次补偿。
                log.warn("分析线程池繁忙，保留 WAITING 任务待下一轮恢复，chartId={}", chartId);
                break;
            }
        }
    }
}
