// Neo4j 多模态融合向量索引初始化脚本
// 在 Neo4j Browser 或 cypher-shell 中逐条执行
// 向量维度: 1024 (multimodal-embedding-v1)
// 相似度函数: cosine
// 存储内容: 实体文字描述 + 图片的融合向量

// 先删除旧的 image 索引（如果存在）
DROP INDEX device_image_index IF EXISTS;
DROP INDEX component_image_index IF EXISTS;
DROP INDEX fault_image_index IF EXISTS;
DROP INDEX solution_image_index IF EXISTS;
DROP INDEX case_record_image_index IF EXISTS;

// 创建新的 multimodal 索引
CREATE VECTOR INDEX device_multimodal_index IF NOT EXISTS
FOR (d:Device) ON (d.multimodal_embedding)
OPTIONS {indexConfig: {
  `vector.dimensions`: 1024,
  `vector.similarity_function`: 'cosine'
}};

CREATE VECTOR INDEX component_multimodal_index IF NOT EXISTS
FOR (c:Component) ON (c.multimodal_embedding)
OPTIONS {indexConfig: {
  `vector.dimensions`: 1024,
  `vector.similarity_function`: 'cosine'
}};

CREATE VECTOR INDEX fault_multimodal_index IF NOT EXISTS
FOR (f:Fault) ON (f.multimodal_embedding)
OPTIONS {indexConfig: {
  `vector.dimensions`: 1024,
  `vector.similarity_function`: 'cosine'
}};

CREATE VECTOR INDEX solution_multimodal_index IF NOT EXISTS
FOR (s:Solution) ON (s.multimodal_embedding)
OPTIONS {indexConfig: {
  `vector.dimensions`: 1024,
  `vector.similarity_function`: 'cosine'
}};

CREATE VECTOR INDEX case_record_multimodal_index IF NOT EXISTS
FOR (cr:CaseRecord) ON (cr.multimodal_embedding)
OPTIONS {indexConfig: {
  `vector.dimensions`: 1024,
  `vector.similarity_function`: 'cosine'
}};

// 验证索引创建成功
SHOW INDEXES WHERE type = 'VECTOR';
