-- =============================================
-- 记忆系统升级 - memory_fact 升级为"单条记忆"模型
-- 文件式记忆协议（MySQL 存储），纯 additive，非破坏性
-- 数据库: fix    字符集: utf8mb4
-- 日期: 2026-06-10
-- =============================================

-- ---------------------------------------------
-- 1. memory_fact 加列：单条记忆模型字段
-- ---------------------------------------------
ALTER TABLE `memory_fact`
    ADD COLUMN `name`         VARCHAR(128) NULL                    COMMENT '记忆名称（单条记忆标识，可读）',
    ADD COLUMN `description`  VARCHAR(255) NULL                    COMMENT '记忆简述',
    ADD COLUMN `type`         VARCHAR(16)  NULL DEFAULT 'project'  COMMENT '记忆类型，默认 project',
    ADD COLUMN `why`          TEXT         NULL                    COMMENT '该记忆为什么重要/产生背景',
    ADD COLUMN `how_to_apply` TEXT         NULL                    COMMENT '该记忆如何应用';

-- ---------------------------------------------
-- 2. memory_fact 加唯一索引 (user_id, name)
--    注意：MySQL 中多个 NULL name 不冲突，符合预期；现有 name 为 NULL 的行不受影响
-- ---------------------------------------------
ALTER TABLE `memory_fact`
    ADD UNIQUE KEY `uk_memory_fact_user_name` (`user_id`, `name`);

-- ---------------------------------------------
-- 2.1 memory_fact.status 扩展枚举值，纳入 'deleted'（软删/作废语义）
--     原为 ENUM('active','superseded')，deleteMemory 需写入 'deleted'，否则 ENUM 截断为空
-- ---------------------------------------------
ALTER TABLE `memory_fact`
    MODIFY COLUMN `status` ENUM('active','superseded','deleted') DEFAULT 'active' COMMENT '状态: active/superseded/deleted';

-- ---------------------------------------------
-- 3.（可选增强）memory_preference 加列
-- ---------------------------------------------
ALTER TABLE `memory_preference`
    ADD COLUMN `name`   VARCHAR(128) NULL                   COMMENT '偏好名称',
    ADD COLUMN `status` VARCHAR(16)  NULL DEFAULT 'active'  COMMENT '状态: active=有效, deleted=已删除';
