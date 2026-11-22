package com.yupi.springbootinit.modules.analysis.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.springbootinit.airuntime.api.AiChatClient;
import com.yupi.springbootinit.airuntime.api.AiChatRequest;
import com.yupi.springbootinit.airuntime.api.AiChatResponse;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.modules.analysis.converter.SpreadsheetDataConverter;
import com.yupi.springbootinit.modules.analysis.prompt.AnalysisPromptBuilder;
import com.yupi.springbootinit.modules.chart.application.AnalysisChartContext;
import com.yupi.springbootinit.modules.chart.application.ChartApplicationService;
import com.yupi.springbootinit.modules.chart.application.CreateAnalysisChartCommand;
import com.yupi.springbootinit.modules.chart.application.ReclaimResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** 智能分析唯一业务编排入口；Controller 和 MQ Consumer 都应复用它。 */
@Service
public class AnalysisTaskApplicationService {
    public static final String WAITING = "WAITING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final int DEFAULT_LEASE_SECONDS = 120;

    private final ChartApplicationService chartApplicationService;
    private final AiChatClient aiChatClient;
    private final SpreadsheetDataConverter dataConverter;
    private final AnalysisPromptBuilder promptBuilder;
    private final AnalysisResultParser resultParser;
    private final AnalysisPlanParser planParser;
    private final SpreadsheetAnalysisService spreadsheetAnalysisService;
    private final int leaseSeconds;
    private final Supplier<String> workerIdGenerator;

    /** Spring 使用共享 ObjectMapper；本地分析服务负责精确聚合和图表构造。 */
    @Autowired
    public AnalysisTaskApplicationService(ChartApplicationService chartApplicationService, AiChatClient aiChatClient,
                                          SpreadsheetDataConverter dataConverter,
                                          AnalysisPromptBuilder promptBuilder,
                                          AnalysisResultParser resultParser,
                                          AnalysisPlanParser planParser,
                                          SpreadsheetAnalysisService spreadsheetAnalysisService,
                                          @Value("${analysis.task.lease-seconds:120}") int leaseSeconds) {
        this(chartApplicationService, aiChatClient, dataConverter, promptBuilder, resultParser,
                planParser, spreadsheetAnalysisService, leaseSeconds,
                AnalysisTaskApplicationService::defaultWorkerId);
    }

    /** 保留轻量手工构造入口，便于现有模块内调用方平滑迁移与单元测试注入。 */
    public AnalysisTaskApplicationService(ChartApplicationService chartApplicationService, AiChatClient aiChatClient,
                                          SpreadsheetDataConverter dataConverter,
                                          AnalysisPromptBuilder promptBuilder,
                                          AnalysisResultParser resultParser) {
        this(chartApplicationService, aiChatClient, dataConverter, promptBuilder, resultParser,
                new AnalysisPlanParser(new ObjectMapper()),
                new SpreadsheetAnalysisService(new ObjectMapper()),
                DEFAULT_LEASE_SECONDS,
                AnalysisTaskApplicationService::defaultWorkerId);
    }

    /** 支持测试注入确定性 workerId 和租约时长。 */
    AnalysisTaskApplicationService(ChartApplicationService chartApplicationService, AiChatClient aiChatClient,
                                   SpreadsheetDataConverter dataConverter,
                                   AnalysisPromptBuilder promptBuilder,
                                   AnalysisResultParser resultParser,
                                   AnalysisPlanParser planParser,
                                   SpreadsheetAnalysisService spreadsheetAnalysisService,
                                   int leaseSeconds,
                                   Supplier<String> workerIdGenerator) {
        this.chartApplicationService = chartApplicationService;
        this.aiChatClient = aiChatClient;
        this.dataConverter = dataConverter;
        this.promptBuilder = promptBuilder;
        this.resultParser = resultParser;
        this.planParser = planParser;
        this.spreadsheetAnalysisService = spreadsheetAnalysisService;
        this.leaseSeconds = leaseSeconds > 0 ? leaseSeconds : DEFAULT_LEASE_SECONDS;
        this.workerIdGenerator = workerIdGenerator != null
                ? workerIdGenerator
                : AnalysisTaskApplicationService::defaultWorkerId;
    }

    /** 查询一批待执行任务供恢复器补偿；任务执行权由 executeExistingTask 原子领取。 */
    public List<Long> findWaitingTaskIds(int limit) {
        return chartApplicationService.findWaitingAnalysisTaskIds(limit);
    }

    /** 回收租约已过期的 RUNNING 任务：未达上限回退 WAITING，达上限原子置为 FAILED。 */
    public ReclaimResult reclaimExpiredRunningTasks(int limit) {
        return chartApplicationService.reclaimExpiredRunningTasks(limit);
    }

    /** 同步执行：先创建持久化任务，再通过统一的状态领取逻辑执行。 */
    public AnalysisTaskResult executeSynchronously(AnalysisTaskCommand command) {
        return executeExistingTask(createWaitingTask(command));
    }

    /** 模型先选择字段与统计口径；本地精确统计后保存快照，再创建 WAITING 任务供解释阶段执行。 */
    public Long createWaitingTask(AnalysisTaskCommand command) {
        AnalysisSummary summary;
        try {
            validate(command);
            String csvData = dataConverter.convert(command.getFile());
            AiChatRequest planningRequest = new AiChatRequest();
            planningRequest.setSystemPrompt(promptBuilder.buildPlanningSystemPrompt());
            planningRequest.setUserMessage(promptBuilder.buildPlanningUserMessage(command.getGoal(),
                    command.getChartType(), spreadsheetAnalysisService.describeDataset(csvData)));
            planningRequest.setTraceId("analysis-plan-" + UUID.randomUUID());
            AiChatResponse planningResponse = aiChatClient.chat(planningRequest);
            AnalysisPlan plan = planParser.parse(planningResponse.getContent());
            summary = spreadsheetAnalysisService.analyze(
                    csvData, plan, command.getGoal(), command.getChartType(), command.getName());
        } catch (IllegalArgumentException e) {
            // 文件限制、模型澄清和无效字段方案需反馈给用户，避免被全局异常处理器隐藏成“系统错误”。
            throw new BusinessException(ErrorCode.PARAMS_ERROR, e.getMessage());
        }
        return chartApplicationService.createAnalysisTask(new CreateAnalysisChartCommand(
                command.getName(), command.getGoal(), command.getChartType(),
                spreadsheetAnalysisService.serialize(summary), command.getUserId()));
    }

    /**
     * 执行已持久化任务。每次尝试生成独立 workerId 作为 Fencing Token，
     * 原子领取失败说明该任务已被处理、仍在运行或已耗尽重试额度，不再调用模型。
     */
    public AnalysisTaskResult executeExistingTask(Long chartId) {
        if (chartId == null || chartId <= 0) {
            throw new IllegalArgumentException("分析任务 ID 无效");
        }
        String workerId = workerIdGenerator.get();
        boolean acquired = chartApplicationService.tryStartAnalysis(chartId, workerId, leaseSeconds);
        AnalysisChartContext context = chartApplicationService.getAnalysisContext(chartId);
        if (!acquired) {
            if (SUCCEEDED.equalsIgnoreCase(context.getStatus()) || RUNNING.equalsIgnoreCase(context.getStatus())) {
                return resultFrom(context);
            }
            throw new IllegalStateException("当前任务状态不可执行: " + context.getStatus());
        }
        return callAiAndPersist(context, workerId);
    }

    /** 失败重试由图表模块原子校验所属用户和 FAILED 状态并重置自动尝试计数，再沿同一执行路径领取。 */
    public AnalysisTaskResult retryFailedTask(Long chartId, Long userId) {
        chartApplicationService.prepareRetry(chartId, userId);
        return executeExistingTask(chartId);
    }

    private AnalysisTaskResult callAiAndPersist(AnalysisChartContext context, String workerId) {
        Long chartId = context.getChartId();
        try {
            AnalysisSummary summary = spreadsheetAnalysisService.restore(context.getChartData(), context.getGoal(),
                    context.getChartType(), context.getName());
            AiChatRequest request = new AiChatRequest();
            request.setSystemPrompt(promptBuilder.buildSystemPrompt());
            request.setUserMessage(promptBuilder.buildUserMessage(
                    context.getGoal(), context.getChartType(), spreadsheetAnalysisService.toPromptSummary(summary)));
            request.setTraceId(String.valueOf(chartId));

            AiChatResponse response = aiChatClient.chat(request);
            AnalysisResult result = resultParser.parse(response.getContent());
            // 图表数值只取本地全量统计，不采信模型编造或误读的图表模板。
            String genChart = spreadsheetAnalysisService.buildChartOption(summary);
            if (!chartApplicationService.completeAnalysis(chartId, workerId, genChart, result.getGenResult())) {
                throw new IllegalStateException("分析结果保存失败，任务租约已失效或状态已变化");
            }
            return new AnalysisTaskResult(chartId, genChart, result.getGenResult());
        } catch (RuntimeException e) {
            // 仅在仍持有当前 workerId 租约时落库 FAILED，避免超时回收后旧执行者覆盖新结果。
            chartApplicationService.failAnalysis(chartId, workerId, e.getMessage());
            throw e;
        }
    }

    private static String defaultWorkerId() {
        return "worker-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private AnalysisTaskResult resultFrom(AnalysisChartContext context) {
        return new AnalysisTaskResult(context.getChartId(), context.getGenChart(), context.getGenResult());
    }

    private void validate(AnalysisTaskCommand command) {
        if (command == null || command.getUserId() == null) {
            throw new IllegalArgumentException("用户不能为空");
        }
        if (command.getFile() == null || command.getFile().isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (command.getGoal() == null || command.getGoal().isBlank()) {
            throw new IllegalArgumentException("分析目标不能为空");
        }
    }
}
