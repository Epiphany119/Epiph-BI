-- Epiph-BI V2.0 Phase 1: 异步任务租约超时回收、workerId Fencing 与最大重试次数限制
-- 执行前请确认已切换到 yubi 数据库。

ALTER TABLE chart
    ADD COLUMN attemptCount INT NOT NULL DEFAULT 0 COMMENT '已尝试执行次数',
    ADD COLUMN maxAttempts INT NOT NULL DEFAULT 3 COMMENT '最大自动尝试次数',
    ADD COLUMN leaseExpireTime DATETIME NULL COMMENT 'RUNNING 租约过期时间',
    ADD COLUMN workerId VARCHAR(64) NULL COMMENT '当前持有租约的执行节点标识',
    ADD INDEX idx_status_lease (status, leaseExpireTime, isDelete);
