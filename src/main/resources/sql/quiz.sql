-- ============ 画像出题/练习系统 ============

-- 一次练习会话
CREATE TABLE IF NOT EXISTS quiz_session (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    mode            VARCHAR(32)  NOT NULL COMMENT 'AI_GENERATE / BANK_PRACTICE',
    status          VARCHAR(32)  NOT NULL COMMENT 'GENERATING / READY / SUBMITTED / FAILED',
    topic_plan      JSON         NULL COMMENT '本次规划/覆盖的主题列表',
    question_count  INT          NOT NULL DEFAULT 0,
    score           INT          NULL COMMENT '答对题数(满分=question_count)',
    correct_count   INT          NULL,
    error_msg       VARCHAR(512) NULL,
    created_at      DATETIME     NOT NULL,
    submitted_at    DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出题/练习会话';

-- 本次会话的题目（答题主体）
CREATE TABLE IF NOT EXISTS quiz_question (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    session_id        BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,
    topic             VARCHAR(128) NOT NULL COMMENT '知识主题(掌握度聚合口径)',
    question_type     VARCHAR(16)  NOT NULL COMMENT 'single / multiple / judge',
    stem              TEXT         NOT NULL,
    options           JSON         NULL COMMENT '[{"key":"A","text":"..."}]',
    correct_answer    VARCHAR(64)  NOT NULL COMMENT '单选/判断=单key；多选=逗号升序如 A,C',
    explanation       TEXT         NULL,
    sources           JSON         NULL COMMENT '溯源 manual/graph/history',
    worker_answer     VARCHAR(64)  NULL,
    is_correct        TINYINT      NULL,
    in_bank           TINYINT      NOT NULL DEFAULT 0,
    bank_question_id  BIGINT       NULL COMMENT '题库练习时引用的 user_question_bank.id',
    sort_order        INT          NOT NULL DEFAULT 0,
    created_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话题目';

-- 个人题库
CREATE TABLE IF NOT EXISTS user_question_bank (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    user_id            BIGINT       NOT NULL,
    topic              VARCHAR(128) NOT NULL,
    question_type      VARCHAR(16)  NOT NULL,
    stem               TEXT         NOT NULL,
    options            JSON         NULL,
    correct_answer     VARCHAR(64)  NOT NULL,
    explanation        TEXT         NULL,
    sources            JSON         NULL,
    folder             VARCHAR(128) NULL COMMENT '收藏分类(二期用，MVP留空)',
    source_session_id  BIGINT       NULL,
    created_at         DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_topic (user_id, topic)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人题库';

-- 掌握度档案
CREATE TABLE IF NOT EXISTS knowledge_mastery (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    topic            VARCHAR(128) NOT NULL,
    correct_count    INT          NOT NULL DEFAULT 0,
    total_count      INT          NOT NULL DEFAULT 0,
    last_quizzed_at  DATETIME     NULL,
    updated_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_topic (user_id, topic)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识掌握度';
