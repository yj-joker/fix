-- 知识文档版本总账本
CREATE TABLE IF NOT EXISTS knowledge_document (
    id                BIGINT PRIMARY KEY COMMENT '雪花ID',
    manual_id         BIGINT       NOT NULL COMMENT '关联 maintenance_manual.id',
    document_id       VARCHAR(64)  NOT NULL COMMENT '传给Python向量库的唯一标识',
    version           INT          NOT NULL DEFAULT 1 COMMENT '版本号',
    file_name         VARCHAR(255) NOT NULL COMMENT '原始文件名',
    file_type         VARCHAR(20)  NOT NULL COMMENT '文件类型: pdf, doc, docx',
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
