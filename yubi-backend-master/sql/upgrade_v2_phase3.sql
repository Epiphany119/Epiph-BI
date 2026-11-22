-- Epiph-BI V2.0 Phase 3: 数据集资产复用与多轮追问下钻血缘
-- 执行前请确认已切换到 yubi 数据库。

CREATE TABLE IF NOT EXISTS bi_dataset (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '数据集ID',
    userId BIGINT NOT NULL COMMENT '所属用户ID',
    `name` VARCHAR(128) NOT NULL COMMENT '数据集名称',
    originalFilename VARCHAR(255) NOT NULL COMMENT '原始文件名',
    rowCount INT NOT NULL DEFAULT 0 COMMENT '数据行数（不含表头）',
    columnCount INT NOT NULL DEFAULT 0 COMMENT '列数',
    schemaProfileJson TEXT NULL COMMENT '列画像摘要JSON',
    csvContent MEDIUMTEXT NOT NULL COMMENT '标准化CSV内容',
    createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updateTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    isDelete TINYINT NOT NULL DEFAULT 0 COMMENT '是否逻辑删除',
    INDEX idx_dataset_user_time (userId, createTime, isDelete)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可复用 BI 数据集表';

ALTER TABLE chart
    ADD COLUMN datasetId BIGINT NULL COMMENT '关联数据集ID',
    ADD COLUMN parentChartId BIGINT NULL COMMENT '父图表ID（用于多轮下钻血缘）',
    ADD INDEX idx_chart_dataset_id (datasetId),
    ADD INDEX idx_chart_parent_id (parentChartId);
