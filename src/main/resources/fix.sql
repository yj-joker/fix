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
