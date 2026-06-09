# 多模态向量融合重构计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `image_embedding`（纯图片向量）重构为 `multimodal_embedding`（文字+图片融合向量），实现真正的跨模态检索。

**Architecture:** 保留 `embedding` 字段（text-embedding-v4，纯文本向量）不变。将 `image_embedding` 重命名为 `multimodal_embedding`，存储由实体文本描述和图片 URL 共同生成的融合向量（multimodal-embedding-v1）。由于百炼 API 限制——单次请求的 input 数组只能包含同类型元素（全是 `{"text":...}` 或全是 `{"image":...}`），因此需分两次调用 API 再加权平均。

**Tech Stack:** Python FastAPI + 阿里云百炼 multimodal-embedding-v1 API + Java Spring Boot + Spring Data Neo4j + Neo4j Vector Index

**关键设计决策：**
- `multimodal-embedding-v1` 的文本输入和图片输入产出的向量在**同一语义空间**（1024维），所以可以直接平均
- 加权策略：纯文字输入权重 0.3，图片权重 0.7（图片对视觉检索更重要）；纯文字时权重 1.0，纯图片时权重 1.0
- 搜索时也用同样逻辑：用户可传文字、图片或两者混合，生成的查询向量与存储的融合向量做 cosine 匹配

---

### Task 1: Python — 新增 `/ai/embedding/multimodal` 融合接口

**Files:**
- Modify: `D:\py\daima\FixAgent\api\main.py` (新增 Request 模型和端点)

- [ ] **Step 1: 在 main.py 的 Embedding 接口区域添加 MultimodalEmbeddingRequest 模型**

在 `TextMultimodalEmbeddingRequest` 类下方添加：

```python
class MultimodalFusionRequest(BaseModel):
    """多模态融合向量化请求体 — 文字+图片混合输入，输出单个融合向量"""
    text: str = ""
    image_urls: List[str] = []
```

- [ ] **Step 2: 添加 `/ai/embedding/multimodal` 端点**

在 `/ai/embedding/text-multimodal` 端点之后添加：

```python
@app.post("/ai/embedding/multimodal")
async def multimodal_fusion_embedding(request: MultimodalFusionRequest):
    """
    多模态融合向量化接口

    将文字描述和图片 URL 融合为单个向量。
    因百炼 API 限制（input 数组元素类型必须一致），
    分两次调用再加权平均：
      1. text → multimodal-embedding-v1（input: [{"text": ...}]）→ text_vec
      2. images → multimodal-embedding-v1（input: [{"image": ...}, ...]）→ 取平均 → img_vec
      3. 加权融合：text_weight * text_vec + img_weight * img_vec

    权重策略：
      - 同时有文字和图片：text_weight=0.3, img_weight=0.7
      - 只有文字：text_weight=1.0
      - 只有图片：img_weight=1.0

    Returns:
        {"vector": [...], "dimension": 1024, "has_text": true, "has_image": true}
    """
    if not request.text and not request.image_urls:
        raise HTTPException(status_code=400, detail="至少需要提供 text 或 image_urls")

    try:
        svc = get_image_embedding()  # 复用 multimodal-embedding-v1 的 client
        headers = {
            "Authorization": f"Bearer {svc.api_key}",
            "Content-Type": "application/json"
        }

        text_vec = None
        img_vec = None

        # 1. 文字向量化（用 multimodal-embedding-v1，不是 text-embedding-v4）
        if request.text:
            params = {"model": svc.model, "input": [{"text": request.text}]}
            resp = await svc.client.post(f"{svc.api_base}/embeddings", headers=headers, json=params)
            resp.raise_for_status()
            data = resp.json()
            if "data" in data and data["data"]:
                text_vec = data["data"][0]["embedding"]

        # 2. 图片向量化
        if request.image_urls:
            vectors = await svc.embed_batch(request.image_urls)
            if vectors:
                # 多张图取平均
                dim = len(vectors[0])
                img_vec = [sum(v[i] for v in vectors) / len(vectors) for i in range(dim)]

        # 3. 加权融合
        if text_vec and img_vec:
            text_w, img_w = 0.3, 0.7
            fused = [text_w * t + img_w * g for t, g in zip(text_vec, img_vec)]
        elif text_vec:
            fused = text_vec
        elif img_vec:
            fused = img_vec
        else:
            raise ValueError("向量化失败：text 和 image 均未产生向量")

        logger.info(f"[multimodal_fusion] text={bool(request.text)} images={len(request.image_urls)} dim={len(fused)}")
        return {
            "vector": fused,
            "dimension": len(fused),
            "has_text": text_vec is not None,
            "has_image": img_vec is not None
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("[multimodal_fusion] error")
        raise HTTPException(status_code=500, detail=str(e))
```

- [ ] **Step 3: 验证端点**

启动 Python 服务后用 curl 测试：
```bash
curl -X POST http://localhost:5000/ai/embedding/multimodal \
  -H "Content-Type: application/json" \
  -d '{"text": "电机轴承过热", "image_urls": []}'
```
预期返回 `{"vector": [...], "dimension": 1024, "has_text": true, "has_image": false}`

- [ ] **Step 4: 提交**

```bash
git add api/main.py
git commit -m "feat: 新增 /ai/embedding/multimodal 融合向量化接口"
```

---

### Task 2: Python — 修改 graph_service.py 索引名

**Files:**
- Modify: `D:\py\daima\FixAgent\services\graph_service.py`（2处索引名）

- [ ] **Step 1: 修改 `search_by_image_vector` 方法的索引名生成逻辑**

将第 375 行：
```python
index_name = f"{entity_type.lower()}_image_index"
```
改为：
```python
index_name = f"{entity_type.lower()}_multimodal_index"
```

- [ ] **Step 2: 修改 `query_diagnosis_by_image_vector` 中硬编码的索引名**

将第 400 行：
```python
CALL db.index.vector.queryNodes('fault_image_index', $limit, $vector)
```
改为：
```python
CALL db.index.vector.queryNodes('fault_multimodal_index', $limit, $vector)
```

- [ ] **Step 3: 提交**

```bash
git add services/graph_service.py
git commit -m "refactor: graph_service 索引名 image_index → multimodal_index"
```

---

### Task 3: Python — 修改 graph_query_tool.py 使用融合向量

**Files:**
- Modify: `D:\py\daima\FixAgent\tools\graph_query_tool.py`（GraphImageSearchTool 类）

- [ ] **Step 1: 修改 GraphImageSearchTool 的参数 schema，增加 text 参数**

将 `get_parameters_schema` 方法（约第 248 行）改为：

```python
def get_parameters_schema(self) -> dict:
    return {
        "type": "object",
        "properties": {
            "image_urls": {
                "type": "array",
                "items": {"type": "string"},
                "description": "图片 URL 列表（MinIO 地址），可选"
            },
            "text": {
                "type": "string",
                "description": "文字描述，可选。与图片一起融合为多模态向量检索"
            },
            "limit": {
                "type": "integer",
                "description": "返回结果数量上限，默认10",
                "default": 10
            }
        }
    }
```

- [ ] **Step 2: 修改 `_execute` 方法，使用融合向量**

将 `_execute` 方法（约第 266 行）改为：

```python
async def _execute(self, image_urls: list = None, text: str = "", limit: int = 10) -> dict:
    try:
        if not image_urls and not text:
            return {"paths": [], "message": "至少需要提供图片或文字描述"}

        # 使用融合向量：文字 + 图片 → 单个多模态向量
        from embeddings.image_embedding import get_image_embedding
        svc = get_image_embedding()
        headers = {
            "Authorization": f"Bearer {svc.api_key}",
            "Content-Type": "application/json"
        }

        text_vec = None
        img_vec = None

        if text:
            params = {"model": svc.model, "input": [{"text": text}]}
            resp = await svc.client.post(f"{svc.api_base}/embeddings", headers=headers, json=params)
            resp.raise_for_status()
            data = resp.json()
            if "data" in data and data["data"]:
                text_vec = data["data"][0]["embedding"]

        if image_urls:
            vectors = await svc.embed_batch(image_urls)
            if vectors:
                dim = len(vectors[0])
                img_vec = [sum(v[i] for v in vectors) / len(vectors) for i in range(dim)]

        if text_vec and img_vec:
            avg_vector = [0.3 * t + 0.7 * g for t, g in zip(text_vec, img_vec)]
        elif text_vec:
            avg_vector = text_vec
        elif img_vec:
            avg_vector = img_vec
        else:
            return {"paths": [], "message": "向量化失败"}

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
                "verified": p.verified
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

- [ ] **Step 3: 提交**

```bash
git add tools/graph_query_tool.py
git commit -m "refactor: GraphImageSearchTool 支持文字+图片融合向量检索"
```

---

### Task 4: Java — 5个实体 `imageEmbedding` → `multimodalEmbedding`

**Files:**
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\entity\Device.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\entity\Component.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\entity\Fault.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\entity\Solution.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\entity\CaseRecord.java`

- [ ] **Step 1: 在所有 5 个实体中将 `image_embedding` → `multimodal_embedding`**

对每个实体，将：
```java
@Property("image_embedding")
private List<Double> imageEmbedding;
```
改为：
```java
@Property("multimodal_embedding")
private List<Double> multimodalEmbedding;
```

涉及文件和大致位置：
- `Fault.java` 第 52-53 行
- `Device.java` — 搜索 `image_embedding` 行
- `Component.java` — 搜索 `image_embedding` 行
- `Solution.java` — 搜索 `image_embedding` 行
- `CaseRecord.java` — 搜索 `image_embedding` 行

- [ ] **Step 2: 提交**

```bash
git add src/main/java/ai/weixiu/entity/
git commit -m "refactor: 5个实体 image_embedding 重命名为 multimodal_embedding"
```

---

### Task 5: Java — `ImageEmbeddingUtils` → `MultimodalEmbeddingUtils`

**Files:**
- Delete: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\utils\ImageEmbeddingUtils.java`
- Create: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\utils\MultimodalEmbeddingUtils.java`

- [ ] **Step 1: 创建新的 MultimodalEmbeddingUtils.java**

```java
package ai.weixiu.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多模态融合向量化工具
 *
 * 调用 Python 端的 /ai/embedding/multimodal 接口，
 * 将实体的文字描述 + 图片 URL 融合为单个向量。
 *
 * 向量由 multimodal-embedding-v1 模型生成（1024维），
 * 文字和图片在同一语义空间，支持跨模态检索。
 */
@Component
@Slf4j
public class MultimodalEmbeddingUtils {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    public MultimodalEmbeddingUtils(
            @Value("${ai.python-service-url:http://localhost:5000}") String pythonServiceUrl,
            ObjectMapper objectMapper
    ) {
        this.webClient = WebClient.create(pythonServiceUrl);
        this.objectMapper = objectMapper;
    }

    /**
     * 将文字描述 + 图片 URL 融合为单个向量
     *
     * @param text      实体的文字描述（如故障名称+描述+类别等拼接文本）
     * @param imageUrls 图片 URL 列表（MinIO 地址），可为 null 或空
     * @return 融合向量，如果输入均为空或调用失败返回 null
     */
    public List<Double> getMultimodalEmbedding(String text, List<String> imageUrls) {
        boolean hasText = text != null && !text.isBlank();
        boolean hasImages = imageUrls != null && !imageUrls.isEmpty();

        if (!hasText && !hasImages) {
            return null;
        }

        try {
            Map<String, Object> body = new HashMap<>();
            if (hasText) {
                body.put("text", text);
            }
            if (hasImages) {
                body.put("image_urls", imageUrls);
            }

            String response = webClient.post()
                    .uri("/ai/embedding/multimodal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);

            JsonNode root = objectMapper.readTree(response);
            JsonNode vectorNode = root.get("vector");

            if (vectorNode == null || !vectorNode.isArray() || vectorNode.isEmpty()) {
                log.warn("多模态融合向量化返回为空");
                return null;
            }

            List<Double> vector = new ArrayList<>(vectorNode.size());
            for (JsonNode v : vectorNode) {
                vector.add(v.asDouble());
            }
            return vector;

        } catch (Exception e) {
            log.error("多模态融合向量化失败: {}", e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 2: 删除旧的 ImageEmbeddingUtils.java**

```bash
git rm src/main/java/ai/weixiu/utils/ImageEmbeddingUtils.java
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/ai/weixiu/utils/
git commit -m "refactor: ImageEmbeddingUtils → MultimodalEmbeddingUtils，改调融合接口"
```

---

### Task 6: Java — 修改 5 个 ServiceImpl 使用 MultimodalEmbeddingUtils

**Files:**
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\service\impl\FaultServiceImpl.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\service\impl\ComponentServiceImpl.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\service\impl\DeviceServiceImpl.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\service\impl\SolutionServiceImpl.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\service\impl\CaseRecordServiceImpl.java`

以 `FaultServiceImpl` 为例说明改动模式，其余 4 个同理。

- [ ] **Step 1: FaultServiceImpl — 替换 import 和字段**

将：
```java
import ai.weixiu.utils.ImageEmbeddingUtils;
```
改为：
```java
import ai.weixiu.utils.MultimodalEmbeddingUtils;
```

将：
```java
private final ImageEmbeddingUtils imageEmbeddingUtils;
```
改为：
```java
private final MultimodalEmbeddingUtils multimodalEmbeddingUtils;
```

- [ ] **Step 2: FaultServiceImpl — 修改 save 方法中的向量化逻辑**

将 `save` 方法中的：
```java
if (fault.getImageUrls() != null && !fault.getImageUrls().isEmpty()) {
    fault.setImageEmbedding(imageEmbeddingUtils.getImageEmbedding(fault.getImageUrls()));
}
```
改为：
```java
// 多模态融合向量：文字描述 + 图片
String embeddingText = buildStringUtils.buildFaultEmbeddingText(fault);
fault.setMultimodalEmbedding(
    multimodalEmbeddingUtils.getMultimodalEmbedding(embeddingText, fault.getImageUrls())
);
```

注意：FaultServiceImpl 中已有 `buildStringUtils`，可直接使用。

- [ ] **Step 3: FaultServiceImpl — 同样修改 update 方法**

与 save 完全相同的改法。

- [ ] **Step 4: ComponentServiceImpl — 同样模式修改**

Component 已有 `buildStringUtils.buildComponentEmbeddingText(component)`，用法相同：
```java
String embeddingText = buildStringUtils.buildComponentEmbeddingText(component);
component.setMultimodalEmbedding(
    multimodalEmbeddingUtils.getMultimodalEmbedding(embeddingText, component.getImageUrls())
);
```

- [ ] **Step 5: DeviceServiceImpl — 修改（Device 没有现成的 buildText 方法）**

Device 没有 `BuildStringUtils` 中的 `buildDeviceEmbeddingText` 方法。在 `BuildStringUtils` 中新增：

```java
public String buildDeviceEmbeddingText(ai.weixiu.entity.Device device) {
    return """
            设备名称：%s
            设备编号：%s
            型号：%s
            位置：%s
            描述：%s
            """.formatted(
            device.getName(),
            device.getCode(),
            device.getModel(),
            device.getLocation(),
            device.getDescription()
    );
}
```

然后 DeviceServiceImpl 中：
```java
String embeddingText = buildStringUtils.buildDeviceEmbeddingText(device);
device.setMultimodalEmbedding(
    multimodalEmbeddingUtils.getMultimodalEmbedding(embeddingText, device.getImageUrls())
);
```

注意：DeviceServiceImpl 可能没有 `buildStringUtils` 依赖，需要加上 `private final BuildStringUtils buildStringUtils;`。

- [ ] **Step 6: SolutionServiceImpl — 修改**

Solution 也没有 `buildText` 方法，在 `BuildStringUtils` 中新增：

```java
public String buildSolutionEmbeddingText(ai.weixiu.entity.Solution solution) {
    return """
            方案标题：%s
            方案步骤：%s
            预计耗时：%s 分钟
            """.formatted(
            solution.getTitle(),
            solution.getSteps(),
            solution.getEstimatedTime()
    );
}
```

SolutionServiceImpl 中同理：
```java
String embeddingText = buildStringUtils.buildSolutionEmbeddingText(solution);
solution.setMultimodalEmbedding(
    multimodalEmbeddingUtils.getMultimodalEmbedding(embeddingText, solution.getImageUrls())
);
```

注意：SolutionServiceImpl 可能没有 `buildStringUtils` 依赖，需要加上。

- [ ] **Step 7: CaseRecordServiceImpl — 修改**

在 `BuildStringUtils` 中新增：

```java
public String buildCaseRecordEmbeddingText(ai.weixiu.entity.CaseRecord caseRecord) {
    return """
            案例标题：%s
            问题描述：%s
            解决方案：%s
            """.formatted(
            caseRecord.getTitle(),
            caseRecord.getDescription(),
            caseRecord.getResolution()
    );
}
```

CaseRecordServiceImpl 中同理。注意需要添加 `buildStringUtils` 依赖。

- [ ] **Step 8: 提交**

```bash
git add src/main/java/ai/weixiu/utils/BuildStringUtils.java
git add src/main/java/ai/weixiu/service/impl/
git commit -m "refactor: 5个ServiceImpl改用MultimodalEmbeddingUtils融合向量化"
```

---

### Task 7: Java — 修改 ImageSearchQuery 支持混合搜索

**Files:**
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\pojo\query\ImageSearchQuery.java`

- [ ] **Step 1: 重命名为 MultimodalSearchQuery 并添加 text 字段**

将 `ImageSearchQuery.java` 重命名为 `MultimodalSearchQuery.java`，内容改为：

```java
package ai.weixiu.pojo.query;

import lombok.Data;
import java.util.List;

@Data
public class MultimodalSearchQuery {
    /** 文字描述（可选，与图片一起融合检索） */
    private String text;
    /** 图片 URL 列表（MinIO 地址，可选） */
    private List<String> imageUrls;
    /** 返回数量，默认 10 */
    private int limit = 10;
    /** 最小相似度，默认 0.5 */
    private double minScore = 0.5;
}
```

- [ ] **Step 2: 删除旧文件**

```bash
git rm src/main/java/ai/weixiu/pojo/query/ImageSearchQuery.java
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/ai/weixiu/pojo/query/
git commit -m "refactor: ImageSearchQuery → MultimodalSearchQuery，增加text字段"
```

---

### Task 8: Java — 修改 GraphQueryService 接口和实现

**Files:**
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\service\GraphQueryService.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\service\impl\GraphQueryServiceImpl.java`

- [ ] **Step 1: 修改接口方法签名**

将 `GraphQueryService.java` 中的：
```java
PageResult<DiagnosisPathVO> findDiagnosisPathsByImage(ImageSearchQuery query);
```
改为：
```java
PageResult<DiagnosisPathVO> findDiagnosisPathsByMultimodal(MultimodalSearchQuery query);
```

更新 import：`ImageSearchQuery` → `MultimodalSearchQuery`。

- [ ] **Step 2: 修改 GraphQueryServiceImpl — import 和字段**

将：
```java
import ai.weixiu.pojo.query.ImageSearchQuery;
import ai.weixiu.utils.ImageEmbeddingUtils;
```
改为：
```java
import ai.weixiu.pojo.query.MultimodalSearchQuery;
import ai.weixiu.utils.MultimodalEmbeddingUtils;
```

将字段：
```java
private final ImageEmbeddingUtils imageEmbeddingUtils;
```
改为：
```java
private final MultimodalEmbeddingUtils multimodalEmbeddingUtils;
```

- [ ] **Step 3: 重写 `findDiagnosisPathsByMultimodal` 方法**

```java
@Override
public PageResult<DiagnosisPathVO> findDiagnosisPathsByMultimodal(MultimodalSearchQuery query) {
    // 1. 调 Python 拿融合向量（文字 + 图片）
    List<Double> vector = multimodalEmbeddingUtils.getMultimodalEmbedding(
        query.getText(), query.getImageUrls()
    );
    if (vector == null || vector.isEmpty()) {
        return emptyResult(0, query.getLimit());
    }

    // 2. 用融合向量在 Fault 的 multimodalEmbedding 索引中检索
    List<DiagnosisPathVO> records = neo4jClient.query("""
            CALL db.index.vector.queryNodes('fault_multimodal_index', $limit, $vector)
            YIELD node AS f, score
            WHERE score >= $minScore
            OPTIONAL MATCH (c:Component)-[:CAUSES]->(f)
            OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
            OPTIONAL MATCH (d:Device)-[:OWNS]->(c)
            RETURN d.id AS deviceId,
                   d.name AS deviceName,
                   c.id AS componentId,
                   c.name AS componentName,
                   f.id AS faultId,
                   f.name AS faultName,
                   f.severity AS faultSeverity,
                   f.image_urls AS faultImageUrls,
                   s.id AS solutionId,
                   s.title AS solutionTitle,
                   s.estimated_time AS estimatedTime,
                   s.verified AS verified,
                   score
            ORDER BY score DESC, s.verified DESC
            LIMIT $limit
            """)
            .bind(vector).to("vector")
            .bind(query.getLimit()).to("limit")
            .bind(query.getMinScore()).to("minScore")
            .fetchAs(DiagnosisPathVO.class)
            .mappedBy((ctx, record) -> {
                DiagnosisPathVO vo = mapDiagnosisPath(record);
                vo.setFaultScore(record.get("score").isNull() ? null : record.get("score").asDouble());
                if (!record.get("faultImageUrls").isNull()) {
                    vo.setFaultImageUrls(record.get("faultImageUrls").asList(org.neo4j.driver.Value::asString));
                }
                return vo;
            })
            .all()
            .stream()
            .toList();

    for (DiagnosisPathVO vo : records) {
        vo.setPathText(buildPathText(vo));
    }

    return pageResult(records, (long) records.size(), 0, query.getLimit());
}
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/ai/weixiu/service/
git commit -m "refactor: GraphQueryService 改用多模态融合向量检索"
```

---

### Task 9: Java — 修改 PathController

**Files:**
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\controller\PathController.java`

- [ ] **Step 1: 修改 import 和端点**

将 `ImageSearchQuery` import 改为 `MultimodalSearchQuery`。

将端点方法改为：
```java
@PostMapping("/multimodal-search")
@Operation(summary = "通过文字+图片多模态融合检索诊断路径")
public Result<PageResult<DiagnosisPathVO>> searchByMultimodal(@RequestBody MultimodalSearchQuery query) {
    return Result.success(graphQueryService.findDiagnosisPathsByMultimodal(query));
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/ai/weixiu/controller/PathController.java
git commit -m "refactor: PathController 端点 image-search → multimodal-search"
```

---

### Task 10: Java — 修改 Repository 层索引名

**Files:**
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\repository\FaultRepository.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\repository\ComponentRepository.java`

- [ ] **Step 1: FaultRepository — 索引名 + 方法名**

将 `getFaultsByImageEmbedding` 方法中的 `'fault_image_index'` 改为 `'fault_multimodal_index'`。
方法名改为 `getFaultsByMultimodalEmbedding`。

- [ ] **Step 2: ComponentRepository — 索引名 + 方法名**

将 `getComponentByImageEmbedding` 方法中的 `'component_image_index'` 改为 `'component_multimodal_index'`。
方法名改为 `getComponentByMultimodalEmbedding`。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/ai/weixiu/repository/
git commit -m "refactor: Repository 层索引名 image_index → multimodal_index"
```

---

### Task 11: Neo4j — 更新向量索引脚本

**Files:**
- Modify: `D:\javaWeb\daima\weixiu\docs\neo4j-image-vector-indexes.cypher`

- [ ] **Step 1: 重写索引脚本**

替换全部内容为：

```cypher
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
```

- [ ] **Step 2: 手动在 Neo4j Browser 中执行**

需要在 Neo4j Browser 中逐条执行上述脚本。先执行 DROP，再执行 CREATE。

- [ ] **Step 3: 提交**

```bash
git add docs/neo4j-image-vector-indexes.cypher
git commit -m "refactor: Neo4j向量索引 image_index → multimodal_index，属性名同步更新"
```

---

### Task 12: 端到端测试验证

- [ ] **Step 1: 启动 Python 服务**

```bash
cd D:\py\daima\FixAgent
python -m uvicorn api.main:app --host 0.0.0.0 --port 5000
```

- [ ] **Step 2: 测试融合向量化接口**

```bash
# 纯文字
curl -X POST http://localhost:5000/ai/embedding/multimodal \
  -H "Content-Type: application/json" \
  -d '{"text": "电机轴承过热故障"}'

# 纯图片
curl -X POST http://localhost:5000/ai/embedding/multimodal \
  -H "Content-Type: application/json" \
  -d '{"image_urls": ["http://your-minio/image.jpg"]}'

# 混合
curl -X POST http://localhost:5000/ai/embedding/multimodal \
  -H "Content-Type: application/json" \
  -d '{"text": "电机轴承过热", "image_urls": ["http://your-minio/image.jpg"]}'
```

- [ ] **Step 3: 在 Neo4j Browser 中执行索引脚本**

逐条执行 Task 11 中的 Cypher 脚本。

- [ ] **Step 4: 启动 Java 服务，通过前端或 Swagger 创建一个带图片的故障实体**

确认 Neo4j 中该故障节点同时有 `embedding`（纯文本）和 `multimodal_embedding`（融合）两个向量。

- [ ] **Step 5: 使用多模态搜索端点验证检索**

```bash
# 用文字搜索
curl -X POST http://localhost:8080/weixiu/path/multimodal-search \
  -H "Content-Type: application/json" \
  -d '{"text": "电机过热", "limit": 5, "minScore": 0.3}'

# 用图片搜索
curl -X POST http://localhost:8080/weixiu/path/multimodal-search \
  -H "Content-Type: application/json" \
  -d '{"imageUrls": ["http://your-minio/image.jpg"], "limit": 5, "minScore": 0.3}'

# 混合搜索
curl -X POST http://localhost:8080/weixiu/path/multimodal-search \
  -H "Content-Type: application/json" \
  -d '{"text": "电机过热", "imageUrls": ["http://your-minio/image.jpg"], "limit": 5, "minScore": 0.3}'
```

预期：都能返回相关的诊断路径。

---

## 改动总览

| 层 | 文件 | 改动 |
|---|---|---|
| Python API | `api/main.py` | 新增 `/ai/embedding/multimodal` 融合端点 |
| Python Service | `services/graph_service.py` | 索引名 `*_image_index` → `*_multimodal_index` |
| Python Tool | `tools/graph_query_tool.py` | GraphImageSearchTool 支持 text + images 混合 |
| Java Entity | 5 个实体 | `imageEmbedding` → `multimodalEmbedding` |
| Java Utils | `ImageEmbeddingUtils` → `MultimodalEmbeddingUtils` | 调融合端点，接收 text + imageUrls |
| Java Utils | `BuildStringUtils` | 新增 Device/Solution/CaseRecord 的 buildText |
| Java ServiceImpl | 5 个 ServiceImpl | 使用 MultimodalEmbeddingUtils，传 text + images |
| Java Query | `ImageSearchQuery` → `MultimodalSearchQuery` | 增加 text 字段 |
| Java Service | `GraphQueryServiceImpl` | 方法名和索引名更新 |
| Java Controller | `PathController` | 端点改为 `/multimodal-search` |
| Java Repository | Fault/Component Repository | 索引名和方法名更新 |
| Neo4j | `neo4j-image-vector-indexes.cypher` | 删旧索引，建新索引（属性名对齐） |
