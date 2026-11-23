-- Epiph-BI: 为用户表新增绑定邮箱列（用于 QQ SMTP 忘记密码找回）
-- 执行前请确认已切换到 yubi 数据库。

ALTER TABLE user
    ADD COLUMN email VARCHAR(128) NULL COMMENT '绑定邮箱（用于找回密码）',
    ADD INDEX idx_user_email (email);
