-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS fix DEFAULT CHARSET=utf8mb4;

-- 用户表建表语句
CREATE TABLE `user` (
                        `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
                        `username` VARCHAR(18) NOT NULL UNIQUE COMMENT '身份证号，登录账号',
                        `name` VARCHAR(50) NOT NULL COMMENT '姓名',
                        `number` VARCHAR(20) NOT NULL UNIQUE COMMENT '工号',
                        `password` VARCHAR(255) NOT NULL COMMENT 'bcrypt加密密码',
                        `gender` TINYINT NOT NULL DEFAULT 0 COMMENT '0=男, 1=女',
                        `type` TINYINT NOT NULL DEFAULT 0 COMMENT '0=员工, 1=管理员',
                        `phone` VARCHAR(11) NOT NULL COMMENT '手机号',
                        `hire_date` DATE NOT NULL COMMENT '入职日期',
                        `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=未激活, 1=已激活',
                        `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                        `last_login_time` DATETIME NULL COMMENT '最后登录时间',
                        INDEX `idx_number` (`number`),
                        INDEX `idx_username` (`username`),
                        INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- AI会话表建表语句
CREATE TABLE ai_session (
                            id BIGINT PRIMARY KEY ,
                            user_id BIGINT NOT NULL,
                            title VARCHAR(255) comment '会话标题',
                            status VARCHAR(32) DEFAULT 'active' comment '会话状态: active 当前会话有效, deleted 当前会话无效',

                            round_count INT DEFAULT 0 comment '当前会话进行了多少轮',

                            summary TEXT comment '旧对话的信息摘要',

                            created_at DATETIME NOT NULL,
                            updated_at DATETIME NOT NULL
);

-- 当前消息历史存储表
CREATE TABLE ai_message (
                            id BIGINT PRIMARY KEY AUTO_INCREMENT,
                            ai_session_id BIGINT NOT NULL,
                            user_id BIGINT NOT NULL,

                            round_no INT NOT NULL comment '当前会话是第几轮',

                            role VARCHAR(32) NOT NULL comment '角色: System, user, assistant, tool',
                            content TEXT NOT NULL comment '消息内容',

                            created_at DATETIME NOT NULL,

                            INDEX idx_session_round (ai_session_id, round_no),
                            INDEX idx_session_created (ai_session_id, created_at)
);
CREATE TABLE memory_fact (
                             id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                             session_id      VARCHAR(64)  NOT NULL COMMENT '会话ID',
                             fact_id         VARCHAR(128) NOT NULL UNIQUE COMMENT '向量库doc_id，用于supersede引用',
                             content         TEXT         NOT NULL COMMENT '事实内容',
                             keywords        VARCHAR(500) DEFAULT '' COMMENT '检索关键词',
                             source_seq_range VARCHAR(50) DEFAULT '' COMMENT '来源对话序号范围（如"3-5"）',
                             status          ENUM('active', 'superseded') DEFAULT 'active' COMMENT '状态',
                             created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
                             superseded_at   DATETIME     NULL COMMENT '被覆盖的时间',

                             INDEX idx_session_status (session_id, status),
                             INDEX idx_fact_id (fact_id)
) COMMENT '提取的事实记忆';
CREATE TABLE memory_preference (
                                   id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                                   user_id        BIGINT            NOT NULL COMMENT '用户ID',
                                   session_id       VARCHAR(64) NOT NULL COMMENT '会话ID',
                                   preference_category     TINYINT(1) DEFAULT 0 COMMENT '偏好类型,0 是用户偏好 1是会话偏好',
                                   content          TEXT        NOT NULL COMMENT '偏好描述',
                                   category         VARCHAR(50) DEFAULT '其他' COMMENT '交互风格|格式要求|工作习惯|关注领域|其他',
                                   consolidation_seq INT        DEFAULT 1 COMMENT '第几次压缩产生的',
                                   created_at       DATETIME    DEFAULT CURRENT_TIMESTAMP,

                                   INDEX idx_session (session_id)
) COMMENT '用户偏好记忆';
CREATE TABLE memory_unresolved (
                                   id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                                   session_id       VARCHAR(64) NOT NULL COMMENT '会话ID',
                                   content          TEXT        NOT NULL COMMENT '待解决描述',
                                   type             VARCHAR(50) DEFAULT '待办' COMMENT '未答复问题|进行中任务|用户待办',
                                   consolidation_seq INT        DEFAULT 1 COMMENT '第几次压缩产生的',
                                   created_at       DATETIME    DEFAULT CURRENT_TIMESTAMP,
                                   status          ENUM('active', 'superseded') DEFAULT 'active' COMMENT '是否被用户放弃',
                                   INDEX idx_session (session_id)
) COMMENT '未完成事项记忆';
# 给会话表添加字段和索引
ALTER TABLE ai_message ADD COLUMN consolidated TINYINT(1) DEFAULT 0 COMMENT '是否已压缩';
ALTER TABLE ai_message ADD INDEX idx_session_consolidated (ai_session_id, consolidated);

# ========== 记忆系统改进 ==========

# 1. memory_fact 新增 user_id 字段 —— 支持跨会话检索事实记忆
# 之前事实只绑定session_id，用户开新会话就丢失了所有历史事实
# 加上user_id后，可以检索该用户在所有会话中积累的事实
ALTER TABLE memory_fact ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0 COMMENT '用户ID，支持跨会话检索';
ALTER TABLE memory_fact ADD INDEX idx_user_status (user_id, status);

# 2. memory_preference 新增 source_type 字段 —— 区分偏好来源的可靠度
# explicit = 用户直接说出来的（如"不要写注释"），高可信度
# inferred = 从行为推断的（如反复追问细节），需多次确认
ALTER TABLE memory_preference ADD COLUMN source_type VARCHAR(20) DEFAULT 'inferred' COMMENT 'explicit=用户明说, inferred=从行为推断';


CREATE TABLE maintenance_manual (
                                    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

                                    manual_name VARCHAR(255) NOT NULL COMMENT '手册名称',
                                    manual_image VARCHAR(255) NOT NULL COMMENT '手册封面',
                                    manual_desc VARCHAR(500) NULL COMMENT '手册描述',

                                    file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
                                    file_type VARCHAR(20) NOT NULL COMMENT '文件类型，如 pdf、doc、docx',
                                    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小，单位字节',
                                    minio_object_name VARCHAR(500) NOT NULL COMMENT 'MinIO对象名',

                                    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0-过时，1-正常',

                                    created_by_id BIGINT NULL COMMENT '上传人ID',

                                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

                                    INDEX idx_status (status),
                                    INDEX idx_created_at (created_at)
) COMMENT='维修手册表';