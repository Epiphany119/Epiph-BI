package com.yupi.springbootinit.modules.analysis.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.springbootinit.airuntime.api.AiChatClient;
import com.yupi.springbootinit.airuntime.api.AiChatRequest;
import com.yupi.springbootinit.airuntime.api.AiChatResponse;
import com.yupi.springbootinit.airuntime.api.AiCostCalculator;
import com.yupi.springbootinit.airuntime.config.AiRuntimeProperties;
import com.yupi.springbootinit.airuntime.exception.AiRuntimeException;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.modules.analysis.converter.SpreadsheetDataConverter;
import com.yupi.springbootinit.modules.analysis.prompt.AnalysisPromptBuilder;
import com.yupi.springbootinit.modules.chart.application.AnalysisChartContext;
import com.yupi.springbootinit.modules.chart.application.ChartApplicationService;
import com.yupi.springbootinit.modules.chart.application.CreateAnalysisChartCommand;
import com.yupi.springbootinit.modules.chart.application.ReclaimResult;
import com.yupi.springbootinit.modules.chart.application.RecordAiUsageCommand;
import com.yupi.springbootinit.modules.file.application.DatasetApplicationService;
import com.yupi.springbootinit.modules.file.application.DatasetContent;
import com.yupi.springbootinit.modules.file.model.vo.DatasetVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** 智能分析唯一业务编排入口；Controller 和 MQ Consumer 都应复用它。 */
@Service
@Slf4j
public class AnalysisTaskApplicationService {
    public static final String WAITING = "WAITING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String STAGE_PLANNING = "PLANNING";
    public static final String STAGE_EXPLAINING = "EXPLAINING";
    public static final String STAGE_FOLLOWUP = "FOLLOWUP";
    public static final int DEFAULT_LEASE_SECONDS = 120;

    private final ChartApplicationService chartApplicationService;
    private final AiChatClient aiChatClient;
    private final SpreadsheetDataConverter dataConverter;
    private final AnalysisPromptBuilder promptBuilder;
    private final AnalysisResultParser resultParser;
    private final AnalysisPlanParser planParser;
    private final SpreadsheetAnalysisService spreadsheetAnalysisService;
    private final AiRuntimeProperties aiRuntimeProperties;
    private final DatasetApplicationService datasetApplicationService;
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
                                          AiRuntimeProperties aiRuntimeProperties,
                                          @Autowired(required = false) DatasetApplicationService datasetApplicationService,
                                          @Value("${analysis.task.lease-seconds:120}") int leaseSeconds) {
        this(chartApplicationService, aiChatClient, dataConverter, promptBuilder, resultParser,
                planParser, spreadsheetAnalysisService, aiRuntimeProperties, datasetApplicationService,
                leaseSeconds, AnalysisTaskApplicationService::defaultWorkerId);
    }

    /** 保留轻量手工构造入口，便于现有模块内调用方平滑迁移与单元测试注入。 */
    public AnalysisTaskApplicationService(ChartApplicationService chartApplicationService, AiChatClient aiChatClient,
                                          SpreadsheetDataConverter dataConverter,
                                          AnalysisPromptBuilder promptBuilder,
                                          AnalysisResultParser resultParser) {
        this(chartApplicationService, aiChatClient, dataConverter, promptBuilder, resultParser,
                new AnalysisPlanParser(new ObjectMapper()),
                new SpreadsheetAnalysisService(new ObjectMapper()),
                new AiRuntimeProperties(),
                null,
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
        this(chartApplicationService, aiChatClient, dataConverter, promptBuilder, resultParser,
                planParser, spreadsheetAnalysisService, new AiRuntimeProperties(), null, leaseSeconds, workerIdGenerator);
    }

    AnalysisTaskApplicationService(ChartApplicationService chartApplicationService, AiChatClient aiChatClient,
                                   SpreadsheetDataConverter dataConverter,
                                   AnalysisPromptBuilder promptBuilder,
                                   AnalysisResultParser resultParser,
                                   AnalysisPlanParser planParser,
                                   SpreadsheetAnalysisService spreadsheetAnalysisService,
                                   AiRuntimeProperties aiRuntimeProperties,
                                   DatasetApplicationService datasetApplicationService,
                                   int leaseSeconds,
                                   Supplier<String> workerIdGenerator) {
        this.chartApplicationService = chartApplicationService;
        this.aiChatClient = aiChatClient;
        this.dataConverter = dataConverter;
        this.promptBuilder = promptBuilder;
        this.resultParser = resultParser;
        this.planParser = planParser;
        this.spreadsheetAnalysisService = spreadsheetAnalysisService;
        this.aiRuntimeProperties = aiRuntimeProperties != null ? aiRuntimeProperties : new AiRuntimeProperties();
        this.datasetApplicationService = datasetApplicationService;
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

    /** 基于已保存的可复用数据集发起同步智能分析。 */
    public AnalysisTaskResult executeByDataset(Long datasetId, String name, String goal, String chartType, Long userId) {
        if (datasetApplicationService == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "数据集服务未启用");
        }
        if (goal == null || goal.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分析目标不能为空");
        }
        DatasetContent dataset = datasetApplicationService.getOwnedDatasetContent(datasetId, userId);
        String resolvedName = name == null || name.isBlank() ? dataset.getName() + " - 分析" : name.trim();
        Long chartId = planAndCreateWaitingTask(
                dataset.getCsvContent(), resolvedName, goal.trim(), chartType,
                userId, dataset.getId(), null, null, STAGE_PLANNING);
        return executeExistingTask(chartId);
    }

    /** 基于已有图表及其关联数据集发起多轮追问/下钻分析，保留父子血缘关系。 */
    public AnalysisTaskResult executeFollowUp(Long parentChartId, String followUpGoal, String chartType,
                                              String name, Long userId) {
        if (datasetApplicationService == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "数据集服务未启用");
        }
        if (parentChartId == null || parentChartId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "父图表 ID 无效");
        }
        if (followUpGoal == null || followUpGoal.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "追问目标不能为空");
        }
        AnalysisChartContext parent = chartApplicationService.getAnalysisContext(parentChartId);
        if (!userId.equals(parent.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权对该图表发起追问下钻");
        }
        if (parent.getDatasetId() == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该历史图表未关联可复用数据集，请重新上传表格后体验多轮下钻");
        }
        DatasetContent dataset = datasetApplicationService.getOwnedDatasetContent(parent.getDatasetId(), userId);
        String resolvedName = name == null || name.isBlank()
                ? (parent.getName() == null ? "下钻分析" : parent.getName() + " · 追问")
                : name.trim();
        Long childChartId = planAndCreateWaitingTask(
                dataset.getCsvContent(), resolvedName, followUpGoal.trim(), chartType,
                userId, dataset.getId(), parentChartId, parent.getChartData(), STAGE_FOLLOWUP);
        return executeExistingTask(childChartId);
    }

    /** 模型先选择字段与统计口径；本地精确统计后保存快照，再沉淀数据集并创建 WAITING 任务供解释阶段执行。 */
    public Long createWaitingTask(AnalysisTaskCommand command) {
        String csvData;
        String originalFilename;
        try {
            validate(command);
            csvData = dataConverter.convert(command.getFile());
            originalFilename = command.getFile().getOriginalFilename();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, e.getMessage());
        }
        Long datasetId = saveDatasetSafely(command.getUserId(), command.getName(), originalFilename, csvData);
        return planAndCreateWaitingTask(csvData, command.getName(), command.getGoal(), command.getChartType(),
                command.getUserId(), datasetId, null, null, STAGE_PLANNING);
    }

    private Long planAndCreateWaitingTask(String csvData, String name, String goal, String chartType,
                                          Long userId, Long datasetId, Long parentChartId,
                                          String parentSummaryJson, String stage) {
        AnalysisSummary summary;
        AiChatResponse planningResponse = null;
        String requestedModel = aiRuntimeProperties.getDefaultModel();
        long startedNs = System.nanoTime();
        try {
            AiChatRequest planningRequest = new AiChatRequest();
            planningRequest.setSystemPrompt(promptBuilder.buildPlanningSystemPrompt());
            String datasetProfile = spreadsheetAnalysisService.describeDataset(csvData);
            if (parentSummaryJson != null) {
                planningRequest.setUserMessage(promptBuilder.buildFollowUpPlanningUserMessage(
                        goal, chartType, parentSummaryJson, datasetProfile));
            } else {
                planningRequest.setUserMessage(promptBuilder.buildPlanningUserMessage(goal, chartType, datasetProfile));
            }
            planningRequest.setTraceId("analysis-plan-" + UUID.randomUUID());
            planningResponse = aiChatClient.chat(planningRequest);
            AnalysisPlan plan = planParser.parse(planningResponse.getContent());
            summary = spreadsheetAnalysisService.analyze(csvData, plan, goal, chartType, name);
        } catch (IllegalArgumentException e) {
            if (planningResponse != null && userId != null) {
                recordUsageSafely(userId, null, stage, requestedModel,
                        planningResponse, true, null, elapsedMs(startedNs));
            }
            throw new BusinessException(ErrorCode.PARAMS_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            if (userId != null) {
                recordUsageSafely(userId, null, stage, requestedModel,
                        null, false, extractErrorCode(e), elapsedMs(startedNs));
            }
            throw e;
        }
        Long chartId = chartApplicationService.createAnalysisTask(new CreateAnalysisChartCommand(
                name, goal, chartType, spreadsheetAnalysisService.serialize(summary),
                userId, datasetId, parentChartId));
        recordUsageSafely(userId, chartId, stage, requestedModel,
                planningResponse, true, null, elapsedMs(startedNs));
        return chartId;
    }

    private Long saveDatasetSafely(Long userId, String chartName, String originalFilename, String csvData) {
        if (datasetApplicationService == null || userId == null) {
            return null;
        }
        try {
            DatasetVO saved = datasetApplicationService.saveFromCsv(userId, chartName, originalFilename, csvData);
            return saved != null ? saved.getId() : null;
        } catch (RuntimeException e) {
            log.warn("自动沉淀数据集失败，不影响主分析任务创建，userId={}", userId, e);
            return null;
        }
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
        String requestedModel = aiRuntimeProperties.getDefaultModel();
        long startedNs = System.nanoTime();
        AiChatResponse response = null;
        try {
            AnalysisSummary summary = spreadsheetAnalysisService.restore(context.getChartData(), context.getGoal(),
                    context.getChartType(), context.getName());
            AiChatRequest request = new AiChatRequest();
            request.setSystemPrompt(promptBuilder.buildSystemPrompt());
            request.setUserMessage(promptBuilder.buildUserMessage(
                    context.getGoal(), context.getChartType(), spreadsheetAnalysisService.toPromptSummary(summary)));
            request.setTraceId(String.valueOf(chartId));

            response = aiChatClient.chat(request);
            AnalysisResult result = resultParser.parse(response.getContent());
            // 图表数值只取本地全量统计，不采信模型编造或误读的图表模板。
            String genChart = spreadsheetAnalysisService.buildChartOption(summary);
            if (!chartApplicationService.completeAnalysis(chartId, workerId, genChart, result.getGenResult())) {
                throw new IllegalStateException("分析结果保存失败，任务租约已失效或状态已变化");
            }
            recordUsageSafely(context.getUserId(), chartId, STAGE_EXPLAINING, requestedModel,
                    response, true, null, elapsedMs(startedNs));
            return new AnalysisTaskResult(chartId, genChart, result.getGenResult());
        } catch (RuntimeException e) {
            recordUsageSafely(context.getUserId(), chartId, STAGE_EXPLAINING, requestedModel,
                    response, false, extractErrorCode(e), elapsedMs(startedNs));
            // 仅在仍持有当前 workerId 租约时落库 FAILED，避免超时回收后旧执行者覆盖新结果。
            chartApplicationService.failAnalysis(chartId, workerId, e.getMessage());
            throw e;
        }
    }

    private void recordUsageSafely(Long userId, Long chartId, String stage, String requestedModel,
                                   AiChatResponse response, boolean success, String errorCode, long fallbackLatencyMs) {
        if (userId == null) {
            return;
        }
        try {
            String actualModel = response != null && response.getModel() != null && !response.getModel().isBlank()
                    ? response.getModel()
                    : requestedModel;
            int inputTokens = response != null && response.getInputTokens() != null ? response.getInputTokens() : 0;
            int outputTokens = response != null && response.getOutputTokens() != null ? response.getOutputTokens() : 0;
            int totalTokens = response != null && response.getTotalTokens() != null
                    ? response.getTotalTokens()
                    : inputTokens + outputTokens;
            int attemptCount = response != null && response.getAttemptCount() != null ? response.getAttemptCount() : 1;
            boolean fallbackUsed = response != null && response.isFallbackUsed();
            long latencyMs = response != null && response.getLatencyMs() != null
                    ? response.getLatencyMs()
                    : fallbackLatencyMs;
            BigDecimal estimatedCost = AiCostCalculator.estimateCostCny(
                    aiRuntimeProperties, actualModel, inputTokens, outputTokens);
            chartApplicationService.recordAiUsage(new RecordAiUsageCommand(
                    userId, chartId, stage, requestedModel, actualModel,
                    fallbackUsed, attemptCount, success, errorCode,
                    inputTokens, outputTokens, totalTokens, estimatedCost, latencyMs));
        } catch (RuntimeException e) {
            log.warn("记录 AI 用量日志失败，不影响主分析流程，chartId={}, stage={}", chartId, stage, e);
        }
    }

    private String extractErrorCode(RuntimeException e) {
        if (e instanceof AiRuntimeException) {
            AiRuntimeException aiEx = (AiRuntimeException) e;
            return aiEx.getErrorCode() != null ? aiEx.getErrorCode().name() : "AI_UNKNOWN_ERROR";
        }
        return e.getClass().getSimpleName();
    }

    private long elapsedMs(long startedNs) {
        return Math.max(0L, (System.nanoTime() - startedNs) / 1_000_000L);
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
