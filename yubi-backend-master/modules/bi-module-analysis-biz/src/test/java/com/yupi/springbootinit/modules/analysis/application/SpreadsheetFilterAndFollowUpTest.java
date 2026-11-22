package com.yupi.springbootinit.modules.analysis.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.springbootinit.airuntime.api.AiChatClient;
import com.yupi.springbootinit.airuntime.api.AiChatResponse;
import com.yupi.springbootinit.airuntime.config.AiRuntimeProperties;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.modules.analysis.converter.SpreadsheetDataConverter;
import com.yupi.springbootinit.modules.analysis.prompt.AnalysisPromptBuilder;
import com.yupi.springbootinit.modules.chart.application.AnalysisChartContext;
import com.yupi.springbootinit.modules.chart.application.ChartApplicationService;
import com.yupi.springbootinit.modules.chart.application.CreateAnalysisChartCommand;
import com.yupi.springbootinit.modules.file.application.DatasetApplicationService;
import com.yupi.springbootinit.modules.file.application.DatasetContent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 本地确定性过滤与多轮追问下钻单元测试。 */
class SpreadsheetFilterAndFollowUpTest {

    private static final String SAMPLE_CSV =
            "区域,渠道,销售额\n"
                    + "华东,线上,1000\n"
                    + "华东,线下,500\n"
                    + "华东,线上,200\n"
                    + "华南,线上,900\n"
                    + "华南,线下,300\n";

    @Test
    void shouldFilterDeterministicallyBeforeAggregation() {
        ObjectMapper objectMapper = new ObjectMapper();
        AnalysisPlanParser parser = new AnalysisPlanParser(objectMapper);
        SpreadsheetAnalysisService analysisService = new SpreadsheetAnalysisService(objectMapper);

        AnalysisPlan plan = parser.parse(
                "{\"status\":\"READY\",\"dimensionField\":\"渠道\",\"seriesField\":null,"
                        + "\"metricField\":\"销售额\",\"aggregation\":\"SUM\",\"chartType\":\"bar\","
                        + "\"chartTitle\":\"华东区大单渠道销售额\",\"reason\":\"筛选华东区且销售额大于等于500\","
                        + "\"filters\":["
                        + "{\"field\":\"区域\",\"operator\":\"EQUALS\",\"values\":[\"华东\"]},"
                        + "{\"field\":\"销售额\",\"operator\":\"GTE\",\"values\":[\"500\"]}"
                        + "]}");

        AnalysisSummary summary = analysisService.analyze(SAMPLE_CSV, plan, "只看华东区金额不低于500的渠道销售额", "柱状图", "测试");

        assertEquals(5, summary.getSourceRowCount());
        assertEquals(2, summary.getFilteredRowCount());
        assertEquals(2, summary.getFilters().size());
        assertEquals(List.of("线上", "线下"), summary.getLabels());
        assertEquals(List.of(new BigDecimal("1000"), new BigDecimal("500")), summary.getValues());
        assertTrue(summary.getNote().contains("筛出 2 条"));
    }

    @Test
    void shouldRejectInvalidFilterFieldNotInHeaders() {
        SpreadsheetAnalysisService analysisService = new SpreadsheetAnalysisService(new ObjectMapper());
        AnalysisPlan plan = new AnalysisPlan();
        plan.setDimensionField("渠道");
        plan.setAggregation("COUNT_ROWS");
        plan.setChartType("bar");
        plan.setFilters(List.of(new FilterCondition("不存在的列", "EQUALS", List.of("华东"))));

        assertThrows(IllegalArgumentException.class,
                () -> analysisService.analyze(SAMPLE_CSV, plan, "测试过滤", "柱状图", "测试"));
    }

    @Test
    void shouldInheritDatasetAndParentChartIdOnFollowUp() {
        ChartApplicationService chartService = mock(ChartApplicationService.class);
        DatasetApplicationService datasetService = mock(DatasetApplicationService.class);
        AiChatClient aiClient = mock(AiChatClient.class);

        when(chartService.getAnalysisContext(10L)).thenReturn(
                new AnalysisChartContext(10L, 100L, "区域销售", "按区域统计销售额", "柱状图",
                        "{\"dimensionField\":\"区域\",\"labels\":[\"华东\",\"华南\"],\"values\":[1700,1200]}",
                        "SUCCEEDED", "{}", "华东最高", 501L, null));
        when(datasetService.getOwnedDatasetContent(501L, 100L)).thenReturn(
                new DatasetContent(501L, 100L, "销售表", "sales.csv", 5, 3, SAMPLE_CSV));

        AiChatResponse planResp = new AiChatResponse();
        planResp.setContent("{\"status\":\"READY\",\"dimensionField\":\"渠道\",\"seriesField\":null,"
                + "\"metricField\":\"销售额\",\"aggregation\":\"SUM\",\"chartType\":\"bar\","
                + "\"chartTitle\":\"华东区渠道拆解\",\"reason\":\"下钻华东区各渠道\","
                + "\"filters\":[{\"field\":\"区域\",\"operator\":\"EQUALS\",\"values\":[\"华东\"]}]}");
        AiChatResponse explainResp = new AiChatResponse();
        explainResp.setContent("{\"genResult\":\"华东区线上销售额 1200 领先线下 500。\"}");
        when(aiClient.chat(any())).thenReturn(planResp, explainResp);

        when(chartService.createAnalysisTask(any())).thenReturn(11L);
        when(chartService.tryStartAnalysis(11L, "worker-f1", 120)).thenReturn(true);
        when(chartService.getAnalysisContext(11L)).thenReturn(
                new AnalysisChartContext(11L, 100L, "区域销售 · 追问", "只看华东区按渠道拆解", "柱状图",
                        SAMPLE_CSV, "RUNNING", null, null, 501L, 10L));
        when(chartService.completeAnalysis(eq(11L), eq("worker-f1"), any(), any())).thenReturn(true);

        ObjectMapper mapper = new ObjectMapper();
        AnalysisTaskApplicationService service = new AnalysisTaskApplicationService(
                chartService, aiClient, new SpreadsheetDataConverter(), new AnalysisPromptBuilder(),
                new AnalysisResultParser(mapper), new AnalysisPlanParser(mapper),
                new SpreadsheetAnalysisService(mapper), new AiRuntimeProperties(),
                datasetService, 120, () -> "worker-f1");

        AnalysisTaskResult result = service.executeFollowUp(10L, "只看华东区按渠道拆解", "柱状图", null, 100L);

        assertEquals(11L, result.getChartId());
        ArgumentCaptor<CreateAnalysisChartCommand> captor = ArgumentCaptor.forClass(CreateAnalysisChartCommand.class);
        verify(chartService).createAnalysisTask(captor.capture());
        assertEquals(501L, captor.getValue().getDatasetId());
        assertEquals(10L, captor.getValue().getParentChartId());
    }

    @Test
    void shouldRejectCrossUserFollowUp() {
        ChartApplicationService chartService = mock(ChartApplicationService.class);
        DatasetApplicationService datasetService = mock(DatasetApplicationService.class);
        when(chartService.getAnalysisContext(10L)).thenReturn(
                new AnalysisChartContext(10L, 100L, "区域销售", "目标", "柱状图",
                        "{}", "SUCCEEDED", "{}", "ok", 501L, null));

        ObjectMapper mapper = new ObjectMapper();
        AnalysisTaskApplicationService service = new AnalysisTaskApplicationService(
                chartService, mock(AiChatClient.class), new SpreadsheetDataConverter(), new AnalysisPromptBuilder(),
                new AnalysisResultParser(mapper), new AnalysisPlanParser(mapper),
                new SpreadsheetAnalysisService(mapper), new AiRuntimeProperties(),
                datasetService, 120, () -> "worker-f1");

        assertThrows(BusinessException.class,
                () -> service.executeFollowUp(10L, "下钻", "柱状图", null, 999L));
    }
}
