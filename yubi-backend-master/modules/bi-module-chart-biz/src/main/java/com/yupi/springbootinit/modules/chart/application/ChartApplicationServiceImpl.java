package com.yupi.springbootinit.modules.chart.application;

import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.service.ChartService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 图表任务持久化边界，所有状态迁移均由带旧状态条件的 SQL 原子完成。 */
@Service
public class ChartApplicationServiceImpl implements ChartApplicationService {
    public static final String WAITING = "WAITING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    private final ChartService chartService;
    private final ChartMapper chartMapper;

    public ChartApplicationServiceImpl(ChartService chartService, ChartMapper chartMapper) {
        this.chartService = chartService;
        this.chartMapper = chartMapper;
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
    public boolean tryStartAnalysis(Long chartId) {
        requireId(chartId);
        return chartMapper.tryStartAnalysis(chartId) == 1;
    }

    @Override
    @Transactional
    public boolean completeAnalysis(Long chartId, String genChart, String genResult) {
        requireId(chartId);
        return chartMapper.completeAnalysis(chartId, genChart, genResult) == 1;
    }

    @Override
    @Transactional
    public boolean failAnalysis(Long chartId, String execMessage) {
        requireId(chartId);
        return chartMapper.failAnalysis(chartId, sanitize(execMessage)) == 1;
    }

    @Override
    public List<Long> findWaitingAnalysisTaskIds(int limit) {
        if (limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "待执行任务查询数量必须在 1 到 100 之间");
        }
        return chartMapper.findWaitingAnalysisTaskIds(limit);
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
