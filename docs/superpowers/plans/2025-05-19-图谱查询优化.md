# Neo4j 图谱查询优化计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构 `findDiagnosisPaths` 和 `findDiagnosisPathsByMultimodal`，解决行膨胀、语义混杂、Cypher 重复、Python 端代码失效等问题，使向量负责单实体匹配、图谱负责关系推理。

**Architecture:** Java 端将 5 分支 Cypher 合并为一条通用查询（动态 WHERE），多模态检索改为"分别检索 Fault + Component → 图谱路径组合"而非单融合向量直查。Python 端修复 `GraphImageSearchTool._execute` 使用正确的 embedding API，并与 `graph_service.py` 的 `find_diagnosis_paths` 五分支逻辑对接。

**Tech Stack:** Java 21, Spring Data Neo4j, Neo4j Cypher, Python 3.11, FastAPI, dashscope SDK

---

## 问题清单与对应 Task

| # | 问题 | 严重度 | Task |
|---|------|--------|------|
| 1 | Python `GraphImageSearchTool._execute` 访问不存在的属性，代码坏了 | 🔴 | Task 1 |
| 2 | Java 5 分支 + 5 count = 10 段近乎相同的 Cypher | 🟡 | Task 2 |
| 3 | `OPTIONAL MATCH Solution` 导致行膨胀，分页失真 | 🟡 | Task 2 |
| 4 | `findDiagnosisPathsByMultimodal` 只查 Fault 索引，丢失部件线索 | 🔴 | Task 3 |
| 5 | 单融合向量语义混杂，不适合多实体问题 | 🔴 | Task 3 |
| 6 | `findDiagnosisPathsByMultimodal` 无分页 | 🟡 | Task 3 |
| 7 | 设备模糊查 `LIMIT 1000` 太宽松 | 🟢 | Task 4 |
| 8 | 部件向量阈值 0.50 太松，故障阈值 0.80 不一致 | 🟢 | Task 4 |
| 9 | Python `graph_service.py` 缺少 `query_diagnosis_path`（旧方法）和 `query_diagnosis_by_image_vector` 方法 | 🔴 | Task 1 |

---

## 文件变更总览

| 文件 | 操作 | 职责 |
|------|------|------|
| `D:\py\daima\FixAgent\tools\graph_query_tool.py` | **修改** | 修复 `GraphImageSearchTool._execute`，改用正确的 embedding 调用 |
| `D:\py\daima\FixAgent\services\graph_service.py` | **修改** | 补齐 `query_diagnosis_path` 和 `query_diagnosis_by_image_vector` 方法 |
| `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\service\impl\GraphQueryServiceImpl.java` | **修改** | 合并 5 分支 Cypher 为通用查询；重构多模态检索 |
| `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\pojo\query\MultimodalSearchQuery.java` | **修改** | 补充分页字段 |
| `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\service\GraphQueryService.java` | **修改** | 接口签名调整 |
| `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\controller\PathController.java` | **修改** | 适配新签名 |

---

### Task 1: 修复 Python 端 GraphImageSearchTool 和 graph_service 缺失方法

**问题：**
1. `GraphImageSearchTool._execute`（第 275-306 行）访问了 `svc.api_key`、`svc.client`、`svc.api_base`，但 `ImageEmbedding` 类没有这些属性，代码完全无法运行
2. `graph_service.py` 缺少 `query_diagnosis_path()` 和 `query_diagnosis_by_image_vector()` 两个方法，但 `GraphQueryTool._execute` 和 `GraphImageSearchTool._execute` 都在调用它们

**Files:**
- Modify: `D:\py\daima\FixAgent\services\graph_service.py:455` （文件末尾，在 `_branch_fault_only` 之后添加）
- Modify: `D:\py\daima\FixAgent\tools\graph_query_tool.py:269-329`

- [ ] **Step 1: 在 `graph_service.py` 添加 `query_diagnosis_path` 方法**

这个方法是 `GraphQueryTool._execute` 调用的（第 120 行 `graph.query_diagnosis_path(keyword, fault_name, limit)`），但 `GraphService` 类没有它。它应该是一个简化版的路径查询——按设备关键字 + 故障名称模糊匹配。

在 `GraphService` 类的 `_has_text` 方法之前添加：

```python
    def query_diagnosis_path(
        self, keyword: str = None, fault_name: str = None, limit: int = 10
    ) -> List[DiagnosisPath]:
        """
        简化版诊断路径查询（供 GraphQueryTool 使用）。
        按设备关键字 + 故障名称模糊匹配，返回 Device→Component→Fault→Solution 路径。
        """
        where_parts = []
        params = {"limit": limit}

        if self._has_text(keyword):
            where_parts.append(
                "(d.name CONTAINS $keyword OR d.code CONTAINS $keyword "
                "OR d.model CONTAINS $keyword OR d.location CONTAINS $keyword)"
            )
            params["keyword"] = keyword

        if self._has_text(fault_name):
            where_parts.append("f.name CONTAINS $faultName")
            params["faultName"] = fault_name

        where_clause = " AND ".join(where_parts) if where_parts else "true"

        cypher = f"""
            MATCH (d:Device)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
            WHERE {where_clause}
            OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
            WITH d, c, f, s
            ORDER BY s.verified DESC, s.estimated_time ASC
            LIMIT $limit
            RETURN d.id AS deviceId, d.name AS deviceName,
                   c.id AS componentId, c.name AS componentName,
                   f.id AS faultId, f.name AS faultName, f.severity AS faultSeverity,
                   s.id AS solutionId, s.title AS solutionTitle,
                   s.estimated_time AS estimatedTime, s.verified AS verified
        """
        rows = self._execute_query(cypher, params)
        return [DiagnosisPath(**_map_path(r)) for r in rows]
```

- [ ] **Step 2: 在 `graph_service.py` 添加 `query_diagnosis_by_image_vector` 方法**

这个方法是 `GraphImageSearchTool._execute` 第 309 行调用的。它用多模态向量在 `fault_multimodal_index` 中检索，然后沿图谱路径展开。

在 `query_diagnosis_path` 方法之后添加：

```python
    def query_diagnosis_by_image_vector(
        self, vector: List[float], limit: int = 10, min_score: float = 0.5
    ) -> List[DiagnosisPath]:
        """
        用多模态向量检索故障，并展开关联的部件和解决方案路径。
        """
        cypher = """
            CALL db.index.vector.queryNodes('fault_multimodal_index', $limit, $vector)
            YIELD node AS f, score
            WHERE score >= $minScore
            OPTIONAL MATCH (c:Component)-[:CAUSES]->(f)
            OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
            OPTIONAL MATCH (d:Device)-[:OWNS]->(c)
            RETURN d.id AS deviceId, d.name AS deviceName,
                   c.id AS componentId, c.name AS componentName,
                   f.id AS faultId, f.name AS faultName, f.severity AS faultSeverity,
                   s.id AS solutionId, s.title AS solutionTitle,
                   s.estimated_time AS estimatedTime, s.verified AS verified,
                   score
            ORDER BY score DESC, s.verified DESC
            LIMIT $limit
        """
        rows = self._execute_query(cypher, {
            "vector": vector, "limit": limit, "minScore": min_score
        })
        results = []
        for r in rows:
            path = DiagnosisPath(**_map_path(r))
            path.fault_score = r.get("score")
            results.append(path)
        return results
```

- [ ] **Step 3: 重写 `GraphImageSearchTool._execute`**

当前代码（第 275-306 行）访问了 `ImageEmbedding` 不存在的属性。改为使用 `TextEmbedding.embed()` 和 `ImageEmbedding.embed_batch()` 的正确 API。

将 `graph_query_tool.py` 中 `async def _execute(self, image_urls: list = None, text: str = "", limit: int = 10) -> dict:` 方法体替换为：

```python
    async def _execute(self, image_urls: list = None, text: str = "", limit: int = 10) -> dict:
        try:
            if not image_urls and not text:
                return {"paths": [], "message": "至少需要提供图片或文字描述"}

            import numpy as np

            text_vec = None
            img_vec = None

            if text:
                from embeddings.text_embedding import get_text_embedding
                text_emb = get_text_embedding()
                text_vec = np.array(await text_emb.embed(text))

            if image_urls:
                from embeddings.image_embedding import get_image_embedding
                img_emb = get_image_embedding()
                img_vecs = await img_emb.embed_batch(image_urls)
                img_vec = np.mean(img_vecs, axis=0)

            # 加权融合
            if text_vec is not None and img_vec is not None:
                fused = 0.3 * text_vec + 0.7 * img_vec
            elif text_vec is not None:
                fused = text_vec
            else:
                fused = img_vec

            # 归一化
            norm = np.linalg.norm(fused)
            if norm > 0:
                fused = fused / norm

            avg_vector = fused.tolist()

            graph = get_graph_service()
            paths = graph.query_diagnosis_by_image_vector(
                vector=avg_vector, limit=limit, min_score=0.5
            )
            formatted = []
            for p in paths:
                formatted.append({
                    "component_name": p.component_name or "未知部件",
                    "fault_name": p.fault_name or "未知故障",
                    "fault_severity": p.fault_severity,
                    "solution_title": p.solution_title or "暂无解决方案",
                    "estimated_time": p.estimated_time,
                    "verified": p.verified,
                    "fault_score": p.fault_score
                })
            return {
                "input_text": bool(text),
                "input_images": len(image_urls or []),
                "paths_found": len(formatted),
                "paths": formatted
            }
        except Exception as e:
            raise ToolException(code="GRAPH_IMAGE_SEARCH_FAILED", message=f"多模态检索诊断路径失败: {e}")
```

- [ ] **Step 4: 验证 Python 编译**

运行：
```bash
cd D:\py\daima\FixAgent
python -m py_compile services/graph_service.py
python -m py_compile tools/graph_query_tool.py
```
预期：两个文件均无报错。

- [ ] **Step 5: 提交**

```bash
git add services/graph_service.py tools/graph_query_tool.py
git commit -m "fix: 修复 GraphImageSearchTool 坏代码，补齐 graph_service 缺失方法"
```

---

### Task 2: Java 端合并 5 分支 Cypher 为通用查询，解决行膨胀

**问题：**
1. `GraphQueryServiceImpl` 有 5 个查询方法 + 5 个 count 方法，共 10 段几乎相同的 Cypher
2. `OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)` 导致行膨胀——一个 Fault 有 3 个 Solution 就产出 3 行，分页和 count 都不准

**解决思路：**
- 合并为一条通用 Cypher，用 `collect()` 聚合 Solution 为列表，消除行膨胀
- 分页基于 `(d, c, f)` 三元组而非膨胀后的行
- DiagnosisPathVO 增加 `solutions` 列表字段，替代单个 solutionId/solutionTitle

**Files:**
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\pojo\vo\DiagnosisPathVO.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\service\impl\GraphQueryServiceImpl.java`

- [ ] **Step 1: 在 DiagnosisPathVO 中增加 Solution 内部类和 solutions 列表字段**

修改 `DiagnosisPathVO.java`，增加嵌套的 `SolutionBrief` 和 `solutions` 字段。保留旧字段（`solutionId`, `solutionTitle`, `estimatedTime`, `verified`）以免破坏前端兼容性，但新逻辑优先使用 `solutions`：

```java
package ai.weixiu.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
public class DiagnosisPathVO {
    private String deviceId;
    private String deviceName;
    private String componentId;
    private String componentName;

    private String faultId;
    private String faultName;
    private String faultSeverity;

    // --- 旧字段（兼容，取 solutions 中第一个 verified=true 或排序最优的） ---
    private String solutionId;
    private String solutionTitle;
    private Integer estimatedTime;
    private Boolean verified;

    // --- 新字段：完整 Solution 列表，消除行膨胀 ---
    private List<SolutionBrief> solutions;

    private List<String> faultImageUrls;
    private List<String> componentImageUrls;

    private String pathText;
    private Double faultScore;
    private Double componentScore;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SolutionBrief {
        private String id;
        private String title;
        private Integer estimatedTime;
        private Boolean verified;
    }
}
```

- [ ] **Step 2: 重写 `GraphQueryServiceImpl` — 删除 10 段重复 Cypher，替换为通用查询方法**

将 `findDiagnosisPaths` 中的 5 分支调用和 10 个私有方法（`getDeviceComponentFaultList`、`getDeviceComponentFaultTotal`……`getGlobalList`、`getGlobalTotal`）替换为一个通用的 `queryPaths` 方法。

核心 Cypher 变化：用 `collect()` 聚合 Solution，避免行膨胀，分页基于 `(d, c, f)` 粒度。

删除以下方法：
- `getDeviceComponentFaultList`, `getDeviceComponentFaultTotal`
- `getComponentFaultList`, `getComponentFaultTotal`
- `getComponentOnlyList`, `getComponentOnlyTotal`
- `getDeviceList`, `getDeviceTotal`
- `getGlobalList`, `getGlobalTotal`

替换为：

```java
    /**
     * 通用诊断路径查询 — 根据有效的 ID 列表动态拼接 WHERE 子句。
     * Solution 用 collect() 聚合，消除行膨胀。
     * 分页基于 (device, component, fault) 三元组。
     */
    private List<DiagnosisPathVO> queryPaths(
            List<String> deviceIds,
            List<String> componentIds,
            List<String> faultIds,
            int skip,
            int limit
    ) {
        // 动态拼 WHERE 子句
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        if (deviceIds != null && !deviceIds.isEmpty()) {
            conditions.add("d.id IN $deviceIds");
            params.put("deviceIds", deviceIds);
        }
        if (componentIds != null && !componentIds.isEmpty()) {
            conditions.add("c.id IN $componentIds");
            params.put("componentIds", componentIds);
        }
        if (faultIds != null && !faultIds.isEmpty()) {
            conditions.add("f.id IN $faultIds");
            params.put("faultIds", faultIds);
        }

        String whereClause = conditions.isEmpty() ? "true" : String.join(" AND ", conditions);
        params.put("skip", skip);
        params.put("limit", limit);

        // 用 collect() 聚合 Solution，按 (d, c, f) 分组
        String cypher = """
                MATCH (d:Device)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
                WHERE %s
                OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
                OPTIONAL MATCH (d)-[hf:HAS_FAULT]->(f)
                WITH d, c, f, hf IS NOT NULL AS hasHistory,
                     collect(DISTINCT {
                         id: s.id,
                         title: s.title,
                         estimatedTime: s.estimated_time,
                         verified: s.verified
                     }) AS solutions
                ORDER BY hasHistory DESC
                SKIP $skip
                LIMIT $limit
                RETURN d.id AS deviceId,
                       d.name AS deviceName,
                       c.id AS componentId,
                       c.name AS componentName,
                       f.id AS faultId,
                       f.name AS faultName,
                       f.severity AS faultSeverity,
                       hasHistory,
                       solutions
                """.formatted(whereClause);

        return neo4jClient.query(cypher)
                .bindAll(params)
                .fetchAs(DiagnosisPathVO.class)
                .mappedBy((ctx, record) -> mapAggregatedPath(record))
                .all()
                .stream()
                .toList();
    }

    private Long queryPathsTotal(
            List<String> deviceIds,
            List<String> componentIds,
            List<String> faultIds
    ) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        if (deviceIds != null && !deviceIds.isEmpty()) {
            conditions.add("d.id IN $deviceIds");
            params.put("deviceIds", deviceIds);
        }
        if (componentIds != null && !componentIds.isEmpty()) {
            conditions.add("c.id IN $componentIds");
            params.put("componentIds", componentIds);
        }
        if (faultIds != null && !faultIds.isEmpty()) {
            conditions.add("f.id IN $faultIds");
            params.put("faultIds", faultIds);
        }

        String whereClause = conditions.isEmpty() ? "true" : String.join(" AND ", conditions);

        String cypher = """
                MATCH (d:Device)-[:OWNS]->(c:Component)-[:CAUSES]->(f:Fault)
                WHERE %s
                RETURN count(DISTINCT [d.id, c.id, f.id]) AS total
                """.formatted(whereClause);

        return neo4jClient.query(cypher)
                .bindAll(params)
                .fetchAs(Long.class)
                .first()
                .orElse(0L);
    }
```

- [ ] **Step 3: 添加新的 `mapAggregatedPath` 映射方法**

替代旧的 `mapDiagnosisPath`，处理 `collect()` 聚合出来的 solutions 列表：

```java
    private DiagnosisPathVO mapAggregatedPath(org.neo4j.driver.Record record) {
        DiagnosisPathVO vo = new DiagnosisPathVO();
        vo.setDeviceId(record.get("deviceId").asString(null));
        vo.setDeviceName(record.get("deviceName").asString(null));
        vo.setComponentId(record.get("componentId").asString(null));
        vo.setComponentName(record.get("componentName").asString(null));
        vo.setFaultId(record.get("faultId").asString(null));
        vo.setFaultName(record.get("faultName").asString(null));
        vo.setFaultSeverity(record.get("faultSeverity").asString(null));

        // 解析聚合的 solutions 列表
        List<DiagnosisPathVO.SolutionBrief> solutions = new ArrayList<>();
        var solutionNodes = record.get("solutions").asList();
        for (Object obj : solutionNodes) {
            if (obj instanceof org.neo4j.driver.Value val) {
                String id = val.get("id").isNull() ? null : val.get("id").asString();
                if (id == null) continue; // 跳过无 Solution 的空记录
                solutions.add(new DiagnosisPathVO.SolutionBrief(
                    id,
                    val.get("title").isNull() ? null : val.get("title").asString(),
                    val.get("estimatedTime").isNull() ? null : val.get("estimatedTime").asInt(),
                    val.get("verified").isNull() ? null : val.get("verified").asBoolean()
                ));
            } else if (obj instanceof Map<?, ?> map) {
                Object id = map.get("id");
                if (id == null) continue;
                solutions.add(new DiagnosisPathVO.SolutionBrief(
                    id.toString(),
                    map.get("title") != null ? map.get("title").toString() : null,
                    map.get("estimatedTime") != null ? ((Number) map.get("estimatedTime")).intValue() : null,
                    map.get("verified") != null ? (Boolean) map.get("verified") : null
                ));
            }
        }

        // 按 verified DESC, estimatedTime ASC 排序
        solutions.sort((a, b) -> {
            int v = Boolean.compare(b.getVerified() != null && b.getVerified(),
                                     a.getVerified() != null && a.getVerified());
            if (v != 0) return v;
            int ea = a.getEstimatedTime() != null ? a.getEstimatedTime() : Integer.MAX_VALUE;
            int eb = b.getEstimatedTime() != null ? b.getEstimatedTime() : Integer.MAX_VALUE;
            return Integer.compare(ea, eb);
        });

        vo.setSolutions(solutions);

        // 兼容旧字段：取排序后第一个 Solution
        if (!solutions.isEmpty()) {
            DiagnosisPathVO.SolutionBrief best = solutions.get(0);
            vo.setSolutionId(best.getId());
            vo.setSolutionTitle(best.getTitle());
            vo.setEstimatedTime(best.getEstimatedTime());
            vo.setVerified(best.getVerified());
        }

        return vo;
    }
```

- [ ] **Step 4: 简化 `findDiagnosisPaths` 方法体**

用新的 `queryPaths` / `queryPathsTotal` 替换 5 分支调用：

```java
    @Override
    public PageResult<DiagnosisPathVO> findDiagnosisPaths(
            String keyword,
            String componentDescription,
            String faultDescription,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(size, 5);
        int skip = safePage * safeSize;

        if (!hasText(faultDescription) && !hasText(componentDescription)) {
            return emptyResult(safePage, safeSize);
        }

        // 1. 设备匹配（限制 top 10，避免 CONTAINS 匹配过多）
        List<String> deviceIds = null;
        if (hasText(keyword)) {
            List<DeviceVO> devices = deviceRepository.getDevices(keyword, 0, 10);
            if (!devices.isEmpty()) {
                deviceIds = devices.stream().map(DeviceVO::getId).toList();
            }
        }

        // 2. 故障向量匹配
        List<String> faultIds = null;
        Map<String, Double> faultScoreMap = Map.of();
        if (hasText(faultDescription)) {
            List<FaultVO> faults = faultService.getFaultByEmbedding(faultDescription, 10L, 0.70);
            if (!faults.isEmpty()) {
                faultIds = faults.stream().map(FaultVO::getId).toList();
                faultScoreMap = faults.stream()
                        .collect(Collectors.toMap(FaultVO::getId, FaultVO::getScore, Math::max));
            }
        }

        // 3. 部件向量匹配
        List<String> componentIds = null;
        Map<String, Double> componentScoreMap = Map.of();
        if (hasText(componentDescription)) {
            List<ComponentVO> components = componentService.getComponentByEmbedding(componentDescription, 10L, 0.70);
            if (!components.isEmpty()) {
                componentIds = components.stream().map(ComponentVO::getId).toList();
                componentScoreMap = components.stream()
                        .collect(Collectors.toMap(ComponentVO::getId, ComponentVO::getScore, Math::max));
            }
        }

        // 如果向量检索没有任何命中
        boolean hasFault = faultIds != null;
        boolean hasComponent = componentIds != null;
        if (!hasFault && !hasComponent) {
            return emptyResult(safePage, safeSize);
        }

        // 4. 通用路径查询（一条 Cypher 替代 5 分支）
        List<DiagnosisPathVO> records = queryPaths(deviceIds, componentIds, faultIds, skip, safeSize);
        Long total = queryPathsTotal(deviceIds, componentIds, faultIds);

        // 5. 补充向量分数和路径文本
        for (DiagnosisPathVO vo : records) {
            vo.setFaultScore(faultScoreMap.get(vo.getFaultId()));
            vo.setComponentScore(componentScoreMap.get(vo.getComponentId()));
            vo.setPathText(buildPathText(vo));
        }

        log.info("诊断路径查询: keyword={} faults={} components={} found={}",
                keyword, faultIds != null ? faultIds.size() : 0,
                componentIds != null ? componentIds.size() : 0, records.size());
        return pageResult(records, total, safePage, safeSize);
    }
```

- [ ] **Step 5: 删除旧的 10 个私有方法和旧的 `mapDiagnosisPath`**

删除以下方法（注意不要删除 `mapAggregatedPath`、`queryPaths`、`queryPathsTotal`）：
- `getDeviceComponentFaultList`
- `getDeviceComponentFaultTotal`
- `getComponentFaultList`
- `getComponentFaultTotal`
- `getComponentOnlyList`
- `getComponentOnlyTotal`
- `getDeviceList`
- `getDeviceTotal`
- `getGlobalList`
- `getGlobalTotal`
- `mapDiagnosisPath`

- [ ] **Step 6: 验证编译**

运行：
```bash
cd D:\javaWeb\daima\weixiu
mvn compile
```
预期：`BUILD SUCCESS`

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "refactor: 合并5分支Cypher为通用查询，collect聚合Solution消除行膨胀"
```

---

### Task 3: 重构 `findDiagnosisPathsByMultimodal` — 分别检索 + 图谱组合

**问题：**
1. 单融合向量语义混杂：用户同时传多个现象/部件时，融合为一个"平均语义"，检索精度下降
2. 只查 `fault_multimodal_index`：用户上传部件照片时无法命中 Component
3. 无分页支持

**解决思路：**
- 图片分别在 `fault_multimodal_index` 和 `component_multimodal_index` 中检索
- 文字也分别检索 Fault 和 Component 的纯文本索引
- 汇总得到 faultIds + componentIds + scores
- 复用 Task 2 的通用 `queryPaths` 方法做图谱路径组合
- MultimodalSearchQuery 增加 page/size

**Files:**
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\pojo\query\MultimodalSearchQuery.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\service\impl\GraphQueryServiceImpl.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\service\GraphQueryService.java`（签名不变，但增加 Javadoc）
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\repository\FaultRepository.java`（已有 `getFaultsByMultimodalEmbedding`，确认可用）
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\repository\ComponentRepository.java`（已有 `getComponentByMultimodalEmbedding`，确认可用）

- [ ] **Step 1: 给 `MultimodalSearchQuery` 增加分页字段**

```java
package ai.weixiu.pojo.query;

import lombok.Data;
import java.util.List;

@Data
public class MultimodalSearchQuery {
    /** 文字描述（可选，与图片一起分别检索） */
    private String text;
    /** 图片 URL 列表（MinIO 地址，可选） */
    private List<String> imageUrls;
    /** 每页数量，默认 10 */
    private int size = 10;
    /** 页码，默认 0 */
    private int page = 0;
    /** 最小相似度，默认 0.5 */
    private double minScore = 0.5;
}
```

注意：删除旧的 `limit` 字段，替换为 `size`（与 `findDiagnosisPaths` 的分页命名一致）。

- [ ] **Step 2: 在 FaultService 和 ComponentService 接口中添加多模态向量检索方法**

`FaultService.java` 添加：
```java
    /**
     * 通过多模态融合向量检索最相似的故障
     */
    List<FaultVO> getFaultByMultimodalEmbedding(List<Double> embedding, Long limit, Double minScore);
```

`ComponentService.java` 添加：
```java
    /**
     * 通过多模态融合向量检索最相似的部件
     */
    List<ComponentVO> getComponentByMultimodalEmbedding(List<Double> embedding, Long limit, Double minScore);
```

- [ ] **Step 3: 在 FaultServiceImpl 和 ComponentServiceImpl 中实现新方法**

`FaultServiceImpl.java` 添加：
```java
    @Override
    public List<FaultVO> getFaultByMultimodalEmbedding(List<Double> embedding, Long limit, Double minScore) {
        return faultRepository.getFaultsByMultimodalEmbedding(embedding, limit, minScore);
    }
```

`ComponentServiceImpl.java` 添加：
```java
    @Override
    public List<ComponentVO> getComponentByMultimodalEmbedding(List<Double> embedding, Long limit, Double minScore) {
        return componentRepository.getComponentByMultimodalEmbedding(embedding, limit, minScore);
    }
```

- [ ] **Step 4: 重写 `findDiagnosisPathsByMultimodal`**

新逻辑：
1. 图片 → 调 Python 获取多模态向量 → 分别在 Fault 和 Component 多模态索引中检索
2. 文字 → 分别在 Fault 和 Component 纯文本索引中检索
3. 合并 faultIds + componentIds（去重，保留最高分数）
4. 复用 `queryPaths` 做图谱路径组合

```java
    @Override
    public PageResult<DiagnosisPathVO> findDiagnosisPathsByMultimodal(MultimodalSearchQuery query) {
        int safePage = Math.max(query.getPage(), 0);
        int safeSize = Math.max(query.getSize(), 5);
        int skip = safePage * safeSize;
        long searchLimit = 10L;
        double minScore = query.getMinScore();

        boolean hasText = hasText(query.getText());
        boolean hasImages = query.getImageUrls() != null && !query.getImageUrls().isEmpty();

        if (!hasText && !hasImages) {
            return emptyResult(safePage, safeSize);
        }

        // ===== 收集 Fault IDs 和 scores =====
        Map<String, Double> faultScoreMap = new HashMap<>();

        // 文字 → Fault 纯文本索引
        if (hasText) {
            List<FaultVO> textFaults = faultService.getFaultByEmbedding(query.getText(), searchLimit, minScore);
            for (FaultVO f : textFaults) {
                faultScoreMap.merge(f.getId(), f.getScore(), Math::max);
            }
        }

        // 图片 → Fault 多模态索引
        if (hasImages) {
            List<Double> multimodalVector = multimodalEmbeddingUtils.getMultimodalEmbedding(null, query.getImageUrls());
            if (multimodalVector != null && !multimodalVector.isEmpty()) {
                List<FaultVO> imgFaults = faultService.getFaultByMultimodalEmbedding(multimodalVector, searchLimit, minScore);
                for (FaultVO f : imgFaults) {
                    faultScoreMap.merge(f.getId(), f.getScore(), Math::max);
                }
            }
        }

        // ===== 收集 Component IDs 和 scores =====
        Map<String, Double> componentScoreMap = new HashMap<>();

        // 文字 → Component 纯文本索引
        if (hasText) {
            List<ComponentVO> textComponents = componentService.getComponentByEmbedding(query.getText(), searchLimit, minScore);
            for (ComponentVO c : textComponents) {
                componentScoreMap.merge(c.getId(), c.getScore(), Math::max);
            }
        }

        // 图片 → Component 多模态索引
        if (hasImages) {
            List<Double> multimodalVector = multimodalEmbeddingUtils.getMultimodalEmbedding(null, query.getImageUrls());
            if (multimodalVector != null && !multimodalVector.isEmpty()) {
                List<ComponentVO> imgComponents = componentService.getComponentByMultimodalEmbedding(multimodalVector, searchLimit, minScore);
                for (ComponentVO c : imgComponents) {
                    componentScoreMap.merge(c.getId(), c.getScore(), Math::max);
                }
            }
        }

        List<String> faultIds = faultScoreMap.isEmpty() ? null : new ArrayList<>(faultScoreMap.keySet());
        List<String> componentIds = componentScoreMap.isEmpty() ? null : new ArrayList<>(componentScoreMap.keySet());

        if (faultIds == null && componentIds == null) {
            return emptyResult(safePage, safeSize);
        }

        // ===== 复用通用图谱路径查询 =====
        List<DiagnosisPathVO> records = queryPaths(null, componentIds, faultIds, skip, safeSize);
        Long total = queryPathsTotal(null, componentIds, faultIds);

        for (DiagnosisPathVO vo : records) {
            vo.setFaultScore(faultScoreMap.get(vo.getFaultId()));
            vo.setComponentScore(componentScoreMap.get(vo.getComponentId()));
            vo.setPathText(buildPathText(vo));
        }

        log.info("多模态路径查询: text={} images={} faults={} components={} found={}",
                hasText, hasImages,
                faultIds != null ? faultIds.size() : 0,
                componentIds != null ? componentIds.size() : 0,
                records.size());
        return pageResult(records, total, safePage, safeSize);
    }
```

**注意**：上面代码中 `multimodalEmbeddingUtils.getMultimodalEmbedding(null, query.getImageUrls())` 只传图片不传文字，因为文字走的是纯文本索引、图片走的是多模态索引，各自独立检索。这避免了语义混杂。

**优化点**：多模态向量调了两次（Fault 一次、Component 一次），但向量相同。应该缓存。将上面代码中的两次调用改为只调一次：

```java
        // 图片 → 多模态向量（只算一次）
        List<Double> multimodalVector = null;
        if (hasImages) {
            multimodalVector = multimodalEmbeddingUtils.getMultimodalEmbedding(null, query.getImageUrls());
        }

        // 然后分别用这个向量查 Fault 和 Component 索引
        if (multimodalVector != null && !multimodalVector.isEmpty()) {
            List<FaultVO> imgFaults = faultService.getFaultByMultimodalEmbedding(multimodalVector, searchLimit, minScore);
            // ...
            List<ComponentVO> imgComponents = componentService.getComponentByMultimodalEmbedding(multimodalVector, searchLimit, minScore);
            // ...
        }
```

- [ ] **Step 5: 验证编译**

运行：
```bash
cd D:\javaWeb\daima\weixiu
mvn compile
```
预期：`BUILD SUCCESS`

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "refactor: 多模态检索改为分别检索Fault+Component，复用通用图谱路径查询，增加分页"
```

---

### Task 4: 收紧检索参数，限制设备模糊查数量

**问题：**
1. 设备模糊查 `deviceRepository.getDevices(keyword, 0, 1000)` — 用户输入 "电" 可能匹配上百台设备
2. 部件向量阈值 0.50 太松（向量空间中 0.5 基本是噪声），故障 0.80 又太紧
3. 向量检索返回 20 条太多，后面传入 Cypher 的 `IN` 列表过长

**Files:**
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\service\impl\GraphQueryServiceImpl.java`

**注意：Task 2 已经在新的 `findDiagnosisPaths` 中应用了这些参数调整（设备 10 条，向量检索 10 条/0.70 阈值）。本 Task 验证这些调整已经生效。**

- [ ] **Step 1: 确认 `findDiagnosisPaths` 中的参数已在 Task 2 中调整**

检查以下三处已在 Task 2 Step 4 中修改：

| 参数 | 旧值 | 新值 | 原因 |
|------|------|------|------|
| 设备模糊查 limit | `1000` | `10` | 避免传入过长的 ID 列表 |
| 故障向量阈值 | `0.80` | `0.70` | 0.80 太紧，容易漏掉相关故障 |
| 部件向量阈值 | `0.50` | `0.70` | 0.50 太松，引入大量噪声 |
| 向量检索 limit | `20L` | `10L` | 减少 IN 列表长度，聚焦 top 结果 |

- [ ] **Step 2: 验证编译**

运行：
```bash
cd D:\javaWeb\daima\weixiu
mvn compile
```
预期：`BUILD SUCCESS`

- [ ] **Step 3: 提交**（如果 Task 2 已覆盖此修改，可合并到 Task 2 的提交中，无需单独提交）

---

## 自检清单

| 检查项 | 状态 |
|--------|------|
| Python `GraphImageSearchTool._execute` 使用正确的 embedding API | Task 1 Step 3 |
| Python `graph_service.py` 补齐 `query_diagnosis_path` 和 `query_diagnosis_by_image_vector` | Task 1 Step 1-2 |
| Java 5 分支 Cypher 合并为通用 `queryPaths` | Task 2 Step 2 |
| Solution 用 `collect()` 聚合，消除行膨胀 | Task 2 Step 2 |
| count 基于 `(d, c, f)` 三元组而非膨胀行 | Task 2 Step 2 |
| DiagnosisPathVO 增加 `solutions` 列表 | Task 2 Step 1 |
| 旧 `solutionId/solutionTitle` 兼容保留 | Task 2 Step 3 |
| `findDiagnosisPathsByMultimodal` 分别检索 Fault + Component | Task 3 Step 4 |
| 多模态检索复用 `queryPaths` | Task 3 Step 4 |
| MultimodalSearchQuery 增加 page/size | Task 3 Step 1 |
| 设备模糊查限制 top 10 | Task 2 Step 4 |
| 向量阈值统一为 0.70 | Task 2 Step 4 |
| 多模态向量只算一次（缓存复用） | Task 3 Step 4 |
| `FaultService`/`ComponentService` 接口新增多模态检索方法 | Task 3 Step 2-3 |
