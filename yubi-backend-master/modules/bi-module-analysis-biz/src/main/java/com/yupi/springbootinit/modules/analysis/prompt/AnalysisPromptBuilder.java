package com.yupi.springbootinit.modules.analysis.prompt;

import org.springframework.stereotype.Component;

/** 只负责构造分析业务 Prompt，不包含供应商 HTTP 或模型协议细节。 */
@Component
public class AnalysisPromptBuilder {
    public String buildSystemPrompt() {
        return "你是数据分析师。系统已对用户上传的全部数据完成精确统计。"
                + "你只负责用中文解释给定的统计摘要，不负责计算或生成图表。"
                + "只能引用摘要中明确出现的字段、类别和数值；不得补造平台、类别、数据、趋势或示例。"
                + "如果摘要提示类别合并或数值缺失，必须如实说明。"
                + "只输出一个合法 JSON 对象，格式为 {\"genResult\":\"分析结论\"}，不要输出 Markdown 或额外字段。";
    }

    /** 将分析目标和受限的本地统计摘要拼接为模型用户消息。 */
    public String buildUserMessage(String goal, String chartType, String summaryJson) {
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("分析目标不能为空");
        String actualGoal = chartType == null || chartType.isBlank() ? goal : goal + "，期望图表类型为" + chartType;
        return "分析需求：\n" + actualGoal + "\n本地统计摘要（来自全部数据行）：\n" + summaryJson;
    }
}
