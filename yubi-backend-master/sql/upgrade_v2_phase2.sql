-- Epiph-BI V2.0 Phase 2: AI 调用用量账单与成本/性能观测
-- 执行前请确认已切换到 yubi 数据库。

CREATE TABLE IF NOT EXISTS ai_usage_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    userId BIGINT NOT NULL COMMENT '用户ID',
    chartId BIGINT NULL COMMENT '关联图表任务ID',
    stage VARCHAR(32) NOT NULL COMMENT 'PLANNING / EXPLAINING / FOLLOWUP',
    requestedModel VARCHAR(64) NOT NULL COMMENT '请求模型',
    actualModel VARCHAR(64) NOT NULL COMMENT '实际执行模型',
    fallbackUsed TINYINT NOT NULL DEFAULT 0 COMMENT '是否触发降级',
    attemptCount INT NOT NULL DEFAULT 1 COMMENT '尝试次数',
    success TINYINT NOT NULL DEFAULT 1 COMMENT '是否成功',
    errorCode VARCHAR(64) NULL COMMENT '失败错误码',
    inputTokens INT NOT NULL DEFAULT 0 COMMENT '输入 Token 数',
    outputTokens INT NOT NULL DEFAULT 0 COMMENT '输出 Token 数',
    totalTokens INT NOT NULL DEFAULT 0 COMMENT '总 Token 数',
    estimatedCostCny DECIMAL(12, 6) NOT NULL DEFAULT 0.000000 COMMENT '估算费用（人民币元）',
    latencyMs BIGINT NOT NULL DEFAULT 0 COMMENT '端到端耗时（毫秒）',
    createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_time (userId, createTime),
    INDEX idx_chart_id (chartId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 调用用量与成本日志';

ALTER TABLE chart
    ADD COLUMN actualModel VARCHAR(64) NULL COMMENT '实际执行的 AI 模型',
    ADD COLUMN totalTokens INT NULL COMMENT '累计消耗 Token 数',
    ADD COLUMN estimatedCostCny DECIMAL(12, 6) NULL COMMENT '累计估算费用（人民币元）',
    ADD COLUMN latencyMs BIGINT NULL COMMENT '累计 AI 调用耗时（毫秒）';
