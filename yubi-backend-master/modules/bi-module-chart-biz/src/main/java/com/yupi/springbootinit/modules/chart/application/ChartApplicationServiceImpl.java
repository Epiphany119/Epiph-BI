package com.yupi.springbootinit.modules.chart.application;

import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.mapper.AiUsageLogMapper;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.model.entity.AiUsageLog;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.vo.AiUsageDailyTrendVO;
import com.yupi.springbootinit.model.vo.AiUsageSummaryVO;
import com.yupi.springbootinit.service.ChartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 图表任务持久化边界，所有状态迁移均由带旧状态与租约条件的 SQL 原子完成。 */
@Service
public class ChartApplicationServiceImpl implements ChartApplicationService {
    public static final String WAITING = "WAITING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final int DEFAULT_TREND_DAYS = 7;
    public static final String EXPIRED_MAX_ATTEMPTS_MESSAGE =
            "任务执行超时且已达最大自动重试次数，请检查数据后手动重试";

    private final ChartService chartService;
    private final ChartMapper chartMapper;
    private final AiUsageLogMapper aiUsageLogMapper;

    @Autowired
    public ChartApplicationServiceImpl(ChartService chartService,
                                       ChartMapper chartMapper,
                                       AiUsageLogMapper aiUsageLogMapper) {
        this.chartService = chartService;
        this.chartMapper = chartMapper;
        this.aiUsageLogMapper = aiUsageLogMapper;
    }

    /** 兼容既有双参单元测试构造入口。 */
    public ChartApplicationServiceImpl(ChartService chartService, ChartMapper chartMapper) {
        this(chartService, chartMapper, null);
    }

    @Override
    @Transactional
    public Long createAnalysisTask(CreateAnalysisChartCommand command) {
        if (command == null || command.getUserId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分析任务缺少所属用户");
        }
        Chart chart = new Chart();
        chart.setName(command.getName());
        chart.setGoal(command.getGoal());
        chart.setChartType(command.getChartType());
        chart.setChartData(command.getChartData());
        chart.setUserId(command.getUserId());
        chart.setStatus(WAITING);
        chart.setAttemptCount(0);
        chart.setMaxAttempts(DEFAULT_MAX_ATTEMPTS);
        if (!chartService.save(chart)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "分析任务创建失败");
        }
        return chart.getId();
    }

    @Override
    public AnalysisChartContext getAnalysisContext(Long chartId) {
        return toContext(getChart(chartId));
    }

    @Override
    @Transactional
    public boolean tryStartAnalysis(Long chartId, String workerId, int leaseSeconds) {
        requireId(chartId);
        requireWorkerId(workerId);
        if (leaseSeconds <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "任务租约时长必须大于 0");
        }
        return chartMapper.tryStartAnalysis(chartId, workerId.trim(), leaseSeconds) == 1;
    }

    @Override
    @Transactional
    public boolean completeAnalysis(Long chartId, String workerId, String genChart, String genResult) {
        requireId(chartId);
        requireWorkerId(workerId);
        return chartMapper.completeAnalysis(chartId, workerId.trim(), genChart, genResult) == 1;
    }

    @Override
    @Transactional
    public boolean failAnalysis(Long chartId, String workerId, String execMessage) {
        requireId(chartId);
        requireWorkerId(workerId);
        return chartMapper.failAnalysis(chartId, workerId.trim(), sanitize(execMessage)) == 1;
    }

    @Override
    public List<Long> findWaitingAnalysisTaskIds(int limit) {
        if (limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "待执行任务查询数量必须在 1 到 100 之间");
        }
        return chartMapper.findWaitingAnalysisTaskIds(limit);
    }

    @Override
    public ReclaimResult reclaimExpiredRunningTasks(int limit) {
        if (limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "超时任务回收数量必须在 1 到 100 之间");
        }
        List<Chart> expiredTasks = chartMapper.findExpiredRunningTasks(limit);
        List<Long> requeuedIds = new ArrayList<>();
        List<Long> failedIds = new ArrayList<>();
        if (expiredTasks == null || expiredTasks.isEmpty()) {
            return new ReclaimResult(requeuedIds, failedIds);
        }
        for (Chart task : expiredTasks) {
            int attempts = task.getAttemptCount() == null ? 0 : task.getAttemptCount();
            int maxAttempts = task.getMaxAttempts() == null ? DEFAULT_MAX_ATTEMPTS : task.getMaxAttempts();
            if (attempts < maxAttempts) {
                if (chartMapper.requeueExpiredRunningTask(task.getId(), task.getWorkerId()) == 1) {
                    requeuedIds.add(task.getId());
                }
            } else {
                if (chartMapper.failExpiredRunningTask(task.getId(), task.getWorkerId(),
                        EXPIRED_MAX_ATTEMPTS_MESSAGE) == 1) {
                    failedIds.add(task.getId());
                }
            }
        }
        return new ReclaimResult(requeuedIds, failedIds);
    }

    @Override
    @Transactional
    public AnalysisChartContext prepareRetry(Long chartId, Long userId) {
        requireId(chartId);
        if (userId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不能为空");
        }
        Chart chart = getChart(chartId);
        if (!userId.equals(chart.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        if (!FAILED.equalsIgnoreCase(chart.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "只有失败任务可以重试");
        }
        if (chartMapper.prepareRetry(chartId, userId) != 1) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "任务状态已变化，请刷新后重试");
        }
        return getAnalysisContext(chartId);
    }

    @Override
    @Transactional
    public void recordAiUsage(RecordAiUsageCommand command) {
        if (command == null || command.getUserId() == null) {
            return;
        }
        BigDecimal cost = command.getEstimatedCostCny() == null ? BigDecimal.ZERO : command.getEstimatedCostCny();
        if (aiUsageLogMapper != null) {
            AiUsageLog log = new AiUsageLog();
            log.setUserId(command.getUserId());
            log.setChartId(command.getChartId());
            log.setStage(command.getStage() == null || command.getStage().isBlank() ? "UNKNOWN" : command.getStage());
            log.setRequestedModel(defaultModelName(command.getRequestedModel()));
            log.setActualModel(defaultModelName(command.getActualModel()));
            log.setFallbackUsed(command.isFallbackUsed() ? 1 : 0);
            log.setAttemptCount(Math.max(1, command.getAttemptCount()));
            log.setSuccess(command.isSuccess() ? 1 : 0);
            log.setErrorCode(command.getErrorCode());
            log.setInputTokens(Math.max(0, command.getInputTokens()));
            log.setOutputTokens(Math.max(0, command.getOutputTokens()));
            log.setTotalTokens(Math.max(0, command.getTotalTokens()));
            log.setEstimatedCostCny(cost);
            log.setLatencyMs(Math.max(0L, command.getLatencyMs()));
            aiUsageLogMapper.insert(log);
        }
        if (command.getChartId() != null && command.getChartId() > 0 && command.isSuccess()) {
            chartMapper.accumulateUsage(
                    command.getChartId(),
                    command.getActualModel(),
                    Math.max(0, command.getTotalTokens()),
                    cost,
                    Math.max(0L, command.getLatencyMs()));
        }
    }

    @Override
    public AiUsageSummaryVO getUserUsageSummary(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不能为空");
        }
        if (aiUsageLogMapper == null) {
            return new AiUsageSummaryVO();
        }
        AiUsageSummaryVO summary = aiUsageLogMapper.selectUserUsageSummary(userId);
        if (summary == null) {
            summary = new AiUsageSummaryVO();
        }
        List<AiUsageDailyTrendVO> trend = aiUsageLogMapper.selectUserDailyTrend(userId, DEFAULT_TREND_DAYS);
        summary.setDailyTrend(trend == null ? Collections.emptyList() : trend);
        return summary;
    }

    private String defaultModelName(String model) {
        return model == null || model.isBlank() ? "unknown" : model.trim();
    }

    private Chart getChart(Long chartId) {
        requireId(chartId);
        Chart chart = chartService.getById(chartId);
        if (chart == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        return chart;
    }

    private void requireId(Long chartId) {
        if (chartId == null || chartId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
    }

    private void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "执行节点标识不能为空");
        }
    }

    private AnalysisChartContext toContext(Chart chart) {
        return new AnalysisChartContext(chart.getId(), chart.getUserId(), chart.getName(), chart.getGoal(),
                chart.getChartType(), chart.getChartData(), chart.getStatus(), chart.getGenChart(), chart.getGenResult());
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "分析执行失败";
        }
        String value = message
                .replaceAll("(?i)(api[-_ ]?key|authorization)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+", "$1=[REDACTED]")
                .replaceAll("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]");
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
