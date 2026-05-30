
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

-- 给user表添加email字段
ALTER TABLE user ADD COLUMN email VARCHAR(255) NULL COMMENT '邮箱';



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
                                    id BIGINT PRIMARY KEY COMMENT '主键ID',

                                    manual_name VARCHAR(255) NOT NULL COMMENT '手册名称',
                                    manual_image VARCHAR(255) NOT NULL COMMENT '手册封面',
                                    manual_desc VARCHAR(500) NULL COMMENT '手册描述',

                                    file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
                                    file_type VARCHAR(20) NOT NULL COMMENT '文件类型，如 pdf',
                                    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小，单位字节',
                                    minio_object_name VARCHAR(500) NOT NULL COMMENT 'MinIO对象名',

                                    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0-过时，1-正常',

                                    created_by_id BIGINT NULL COMMENT '上传人ID',

                                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

                                    INDEX idx_status (status),
                                    INDEX idx_created_at (created_at)
) COMMENT='维修手册表';

-- 知识文档版本总账本
CREATE TABLE IF NOT EXISTS knowledge_document (
                                                  id                BIGINT PRIMARY KEY COMMENT '雪花ID',
                                                  manual_id         BIGINT       NOT NULL COMMENT '关联 maintenance_manual.id',
                                                  document_id       VARCHAR(64)  NOT NULL COMMENT '传给Python向量库的唯一标识',
                                                  version           INT          NOT NULL DEFAULT 1 COMMENT '版本号',
                                                  file_name         VARCHAR(255) NOT NULL COMMENT '原始文件名',
                                                  file_type         VARCHAR(20)  NOT NULL COMMENT '文件类型: pdf',
                                                  file_size         BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
                                                  minio_object_name VARCHAR(500) NOT NULL COMMENT 'MinIO私有桶对象名',
                                                  status            VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending/parsing/indexing/ready/failed',
                                                  error_message     TEXT         NULL COMMENT '失败原因',
                                                  text_count        INT          NOT NULL DEFAULT 0 COMMENT '入库文本块数',
                                                  image_count       INT          NOT NULL DEFAULT 0 COMMENT '入库图片数',
                                                  table_count       INT          NOT NULL DEFAULT 0 COMMENT '入库表格数',
                                                  created_by_id     BIGINT       NULL COMMENT '上传人ID',
                                                  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                                  INDEX idx_manual_id (manual_id),
                                                  INDEX idx_document_id (document_id),
                                                  INDEX idx_status (status)
) COMMENT='知识文档版本总账本';

-- 给 maintenance_manual 加 active_document_id 字段
ALTER TABLE maintenance_manual
    ADD COLUMN active_document_id BIGINT NULL COMMENT '当前可用版本 knowledge_document.id';

-- 版本号唯一索引：防止并发上传产生重复版本号（与分布式锁双保险）
ALTER TABLE knowledge_document
    ADD UNIQUE INDEX uk_manual_version (manual_id, version);

-- 旧文件字段改为可空（新逻辑下文件信息存 knowledge_document 表）
ALTER TABLE maintenance_manual MODIFY COLUMN file_name VARCHAR(255) NULL COMMENT '原始文件名（旧数据兼容）';
ALTER TABLE maintenance_manual MODIFY COLUMN file_type VARCHAR(20) NULL COMMENT '文件类型（旧数据兼容）';
ALTER TABLE maintenance_manual MODIFY COLUMN file_size BIGINT NULL DEFAULT 0 COMMENT '文件大小（旧数据兼容）';
ALTER TABLE maintenance_manual MODIFY COLUMN minio_object_name VARCHAR(500) NULL COMMENT 'MinIO对象名（旧数据兼容）';


-- =============================================
-- 检修任务 + 步骤执行记录
-- =============================================

CREATE TABLE IF NOT EXISTS `maintenance_task` (
                                                  `id`               BIGINT       NOT NULL COMMENT '雪花ID',
                                                  `task_number`      VARCHAR(30)  NOT NULL COMMENT '任务编号 MT-yyyyMMdd-xxx',
                                                  `device_id`        VARCHAR(64)  DEFAULT NULL COMMENT '设备ID（图谱节点ID）',
                                                  `device_name`      VARCHAR(200) DEFAULT NULL COMMENT '设备名称',
                                                  `fault_description` TEXT        NOT NULL COMMENT '故障描述',
                                                  `urgency_level`    INT          NOT NULL DEFAULT 1 COMMENT '紧急等级 0低 1普通 2紧急',
                                                  `report_images`    JSON         DEFAULT NULL COMMENT '报修图片URL列表',
                                                  `status`           VARCHAR(30)  NOT NULL DEFAULT 'CREATED' COMMENT '状态: CREATED/GENERATING/GENERATED/GENERATE_FAILED/EXECUTING/CLOSED',
                                                  `step_count`       INT          NOT NULL DEFAULT 0 COMMENT '步骤总数（冗余）',
                                                  `reporter_id`      BIGINT       DEFAULT NULL COMMENT '报修人ID',
                                                  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                  `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                                  PRIMARY KEY (`id`),
                                                  UNIQUE KEY `uk_task_number` (`task_number`),
                                                  KEY `idx_status` (`status`),
                                                  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检修任务';

CREATE TABLE IF NOT EXISTS `task_step_record` (
                                                  `id`            BIGINT       NOT NULL COMMENT '雪花ID',
                                                  `task_id`       BIGINT       NOT NULL COMMENT '所属任务ID',
                                                  `sort_order`    INT          NOT NULL COMMENT '步骤序号（从1开始）',
                                                  `title`         VARCHAR(200) NOT NULL COMMENT '步骤标题',
                                                  `content`       TEXT         DEFAULT NULL COMMENT '步骤详细说明',
                                                  `safety_note`   TEXT         DEFAULT NULL COMMENT '安全注意事项',
                                                  `require_photo` TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否要求拍照',
                                                  `require_note`  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否要求备注',
                                                  `estimated_minutes` INT      DEFAULT NULL COMMENT '预估耗时(分钟)',
                                                  `status`        VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/COMPLETED/SKIPPED',
                                                  `images`        JSON         DEFAULT NULL COMMENT '工人上传的照片',
                                                  `note`          TEXT         DEFAULT NULL COMMENT '工人填写的备注',
                                                  `completed_at`  DATETIME     DEFAULT NULL COMMENT '完成时间',
                                                  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                  PRIMARY KEY (`id`),
                                                  KEY `idx_task_id` (`task_id`),
                                                  KEY `idx_task_order` (`task_id`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务步骤执行记录';

-- =============================================
-- AI 验收字段
-- =============================================

ALTER TABLE task_step_record
    ADD COLUMN `ai_pass`       TINYINT(1)   DEFAULT NULL COMMENT 'AI验证是否通过',
    ADD COLUMN `ai_confidence` DECIMAL(4,3) DEFAULT NULL COMMENT 'AI验证置信度(0-1)',
    ADD COLUMN `ai_reason`     TEXT         DEFAULT NULL COMMENT 'AI验证理由';

-- =============================================
-- 步骤来源溯源 + 生成置信度
-- =============================================

ALTER TABLE task_step_record
    ADD COLUMN `sources`              JSON         DEFAULT NULL COMMENT '步骤来源引用(手册/图谱)',
    ADD COLUMN `generate_confidence`  DECIMAL(4,3) DEFAULT NULL COMMENT '生成置信度(0-1)';

-- =============================================
-- 标准作业规程 (Phase 1)
-- =============================================

CREATE TABLE IF NOT EXISTS `standard_procedure` (
    `id`                BIGINT       NOT NULL COMMENT '雪花ID',
    `name`              VARCHAR(200) NOT NULL COMMENT '规程名称',
    `device_type`       VARCHAR(100) DEFAULT NULL COMMENT '设备类型',
    `maintenance_level` VARCHAR(20)  DEFAULT NULL COMMENT '检修等级: ROUTINE/MINOR/MAJOR',
    `description`       TEXT         DEFAULT NULL COMMENT '规程说明',
    `version`           INT          NOT NULL DEFAULT 1 COMMENT '版本号',
    `status`            VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT/PUBLISHED/ARCHIVED',
    `source_type`       VARCHAR(20)  NOT NULL DEFAULT 'MANUAL_CREATE' COMMENT '来源: MANUAL_CREATE/AI_GENERATE/TASK_PROMOTE',
    `source_task_id`    BIGINT       DEFAULT NULL COMMENT '源任务ID(TASK_PROMOTE时)',
    `total_steps`       INT          NOT NULL DEFAULT 0 COMMENT '步骤总数',
    `created_by`        BIGINT       DEFAULT NULL COMMENT '创建人ID',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_device_level` (`device_type`, `maintenance_level`),
    KEY `idx_status` (`status`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准作业规程';

CREATE TABLE IF NOT EXISTS `procedure_step` (
    `id`                BIGINT       NOT NULL COMMENT '雪花ID',
    `procedure_id`      BIGINT       NOT NULL COMMENT '关联规程ID',
    `step_order`        INT          NOT NULL COMMENT '步骤序号(从1开始)',
    `title`             VARCHAR(200) NOT NULL COMMENT '步骤标题',
    `content`           TEXT         DEFAULT NULL COMMENT '操作详细内容',
    `safety_note`       TEXT         DEFAULT NULL COMMENT '安全注意事项',
    `is_checkpoint`     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否合规检查点(Phase3)',
    `checkpoint_items`  JSON         DEFAULT NULL COMMENT '检查项列表(Phase3)',
    `estimated_minutes` INT          DEFAULT NULL COMMENT '预估耗时(分钟)',
    `reference_images`  JSON         DEFAULT NULL COMMENT '参考图片URL列表',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_procedure_order` (`procedure_id`, `step_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规程步骤模板';

-- =============================================
-- Phase 2: 任务关联标准规程 + 检修等级
-- =============================================
ALTER TABLE maintenance_task
    ADD COLUMN `procedure_id`       BIGINT       DEFAULT NULL COMMENT '关联的标准规程ID（从规程创建时不为空）',
    ADD COLUMN `maintenance_level`  VARCHAR(20)  DEFAULT NULL COMMENT '检修等级: ROUTINE(日常保养)/MINOR(小修)/MAJOR(大修)';

-- =============================================
-- Phase 3: 合规检查点
-- =============================================
ALTER TABLE task_step_record
    ADD COLUMN `is_checkpoint`        TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否为合规检查点',
    ADD COLUMN `checkpoint_items`     JSON        DEFAULT NULL COMMENT '检查项列表 ["已断电确认","已佩戴护目镜",...]',
    ADD COLUMN `checkpoint_confirmed` TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '工人是否已确认所有检查项';

-- =============================================
-- Phase 4: 知识沉淀（AI图谱线索 + 沉淀接口）
-- =============================================
ALTER TABLE maintenance_task
    ADD COLUMN `graph_extraction` JSON DEFAULT NULL COMMENT 'AI提取的图谱线索(设备/部件/故障/方案)，沉淀时供管理员确认';

-- =============================================
-- Phase 5: AI增强规程（生成模式标记）
-- =============================================
ALTER TABLE maintenance_task
    ADD COLUMN `generate_mode` VARCHAR(20) DEFAULT NULL COMMENT '生成模式: PROCEDURE_COPY(直接拷贝) / AI_ADAPT(AI基于规程微调) / AI_GENERATE(AI从零生成)';

-- =============================================
-- Phase 6: 知识沉淀标记
-- =============================================
ALTER TABLE maintenance_task
    ADD COLUMN `promoted_procedure` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已沉淀为标准规程',
    ADD COLUMN `promoted_graph`     TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已沉淀到知识图谱';
