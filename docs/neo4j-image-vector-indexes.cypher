// Neo4j 图片向量索引初始化脚本
// 在 Neo4j Browser 或 cypher-shell 中逐条执行
// 向量维度: 1024 (multimodal-embedding-v1)
// 相似度函数: cosine

CREATE VECTOR INDEX device_image_index IF NOT EXISTS
FOR (d:Device) ON (d.image_embedding)
OPTIONS {indexConfig: {
  `vector.dimensions`: 1024,
  `vector.similarity_function`: 'cosine'
}};

CREATE VECTOR INDEX component_image_index IF NOT EXISTS
FOR (c:Component) ON (c.image_embedding)
OPTIONS {indexConfig: {
  `vector.dimensions`: 1024,
  `vector.similarity_function`: 'cosine'
}};

CREATE VECTOR INDEX fault_image_index IF NOT EXISTS
FOR (f:Fault) ON (f.image_embedding)
OPTIONS {indexConfig: {
  `vector.dimensions`: 1024,
  `vector.similarity_function`: 'cosine'
}};

CREATE VECTOR INDEX solution_image_index IF NOT EXISTS
FOR (s:Solution) ON (s.image_embedding)
OPTIONS {indexConfig: {
  `vector.dimensions`: 1024,
  `vector.similarity_function`: 'cosine'
}};

CREATE VECTOR INDEX case_record_image_index IF NOT EXISTS
FOR (cr:CaseRecord) ON (cr.image_embedding)
OPTIONS {indexConfig: {
  `vector.dimensions`: 1024,
  `vector.similarity_function`: 'cosine'
}};

// 验证索引创建成功
SHOW INDEXES WHERE type = 'VECTOR';
