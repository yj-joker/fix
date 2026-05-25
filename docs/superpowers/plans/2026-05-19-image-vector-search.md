# Neo4j 图片向量跨模态检索 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Neo4j 的 5 种实体（Device/Component/Fault/Solution/CaseRecord）添加图片和图片向量，实现跨模态检索：上传图片 → 检索图谱诊断路径，输入文字 → 检索相关图片。

**Architecture:** Java 端负责图片上传（MinIO）、调 Python 拿图片向量、存入 Neo4j。Python 端统一提供 Embedding 接口（复用现有阿里云百炼 `multimodal-embedding-v1`），并在 `graph_service.py` 中新增基于图片向量的 Neo4j 检索。保留两套向量：`embedding`（text-embedding-v4，纯文字检索）+ `imageEmbedding`（multimodal-embedding-v1，跨模态检索）。

**Tech Stack:** Java Spring Boot + Spring Data Neo4j, Python FastAPI, 阿里云百炼 multimodal-embedding-v1, MinIO, Neo4j Vector Index

---

## File Structure

### Python 端（D:\py\daima\FixAgent）

| 文件 | 动作 | 职责 |
|------|------|------|
| `api/main.py` | 修改 | 新增 `/ai/embedding/image` 和 `/ai/embedding/text-multimodal` 接口 |
| `services/graph_service.py` | 修改 | 新增图片向量检索方法 + 跨模态诊断路径查询 |
| `tools/graph_query_tool.py` | 修改 | 新增图片检索工具供 Agent RAG 使用 |

### Java 端（D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu）

| 文件 | 动作 | 职责 |
|------|------|------|
| `entity/Device.java` | 修改 | 加 imageUrls + imageEmbedding |
| `entity/Component.java` | 修改 | 加 imageUrls + imageEmbedding |
| `entity/Fault.java` | 修改 | 加 imageUrls + imageEmbedding |
| `entity/Solution.java` | 修改 | 加 imageUrls + imageEmbedding |
| `entity/CaseRecord.java` | 修改 | 加 imageUrls + imageEmbedding |
| `pojo/dto/DeviceDTO.java` | 修改 | 加 imageUrls |
| `pojo/dto/ComponentDTO.java` | 修改 | 加 imageUrls |
| `pojo/dto/FaultDTO.java` | 修改 | 加 imageUrls |
| `pojo/dto/SolutionDTO.java` | 修改 | 加 imageUrls |
| `pojo/dto/CaseRecordDTO.java` | 修改 | 加 imageUrls |
| `pojo/vo/DeviceVO.java` | 修改 | 加 imageUrls |
| `pojo/vo/ComponentVO.java` | 修改 | 加 imageUrls |
| `pojo/vo/FaultVO.java` | 修改 | 加 imageUrls |
| `pojo/vo/SolutionVO.java` | 修改 | 加 imageUrls |
| `utils/ImageEmbeddingUtils.java` | 新建 | 调 Python `/ai/embedding/image` 拿图片向量 |
| `service/impl/DeviceServiceImpl.java` | 修改 | save/update 时调图片向量化 |
| `service/impl/ComponentServiceImpl.java` | 修改 | save/update 时调图片向量化 |
| `service/impl/FaultServiceImpl.java` | 修改 | save/update 时调图片向量化 |
| `service/impl/SolutionServiceImpl.java` | 修改 | save/update 时调图片向量化 |
| `service/impl/CaseRecordServiceImpl.java` | 修改 | save/update 时调图片向量化 |
| `repository/FaultRepository.java` | 修改 | 新增 imageEmbedding 向量检索查询 |
| `repository/ComponentRepository.java` | 修改 | 新增 imageEmbedding 向量检索查询 |
| `service/impl/GraphQueryServiceImpl.java` | 修改 | 新增图片向量检索 → 图谱路径扩展 |
| `service/GraphQueryService.java` | 修改 | 接口新增方法签名 |
| `controller/PathController.java` | 修改 | 新增图片检索图谱路径的接口 |
| `pojo/query/ImageSearchQuery.java` | 新建 | 图片检索请求参数 |
| `pojo/vo/DiagnosisPathVO.java` | 修改 | 加 imageUrls 字段 |

---

### Task 1: Python 端 — 新增 Embedding 接口

**Files:**
- Modify: `D:\py\daima\FixAgent\api\main.py`

这两个接口供 Java 端调用，统一由 Python 端生成向量。

- [ ] **Step 1: 在 main.py 末尾（global_exception_handler 之前）新增两个接口**

```python
from embeddings.image_embedding import get_image_embedding
from embeddings.text_embedding import get_text_embedding


class ImageEmbeddingRequest(BaseModel):
    """图片向量化请求"""
    image_urls: List[str]


class TextMultimodalEmbeddingRequest(BaseModel):
    """文字跨模态向量化请求（用 multimodal-embedding-v1 生成，和图片在同一向量空间）"""
    text: str


@app.post("/ai/embedding/image")
async def embedding_image(request: ImageEmbeddingRequest):
    """
    图片向量化接口

    Java 端上传图片到 MinIO 后，将图片 URL 列表发到此接口，
    返回每张图片的向量。Java 端取平均值存入 Neo4j 的 imageEmbedding 字段。

    使用模型：multimodal-embedding-v1（和文字跨模态接口同一模型，同一向量空间）
    """
    try:
        service = get_image_embedding()
        vectors = await service.embed_batch(request.image_urls)
        return {
            "vectors": vectors,
            "dimension": len(vectors[0]) if vectors else 0,
            "count": len(vectors)
        }
    except Exception as e:
        logger.exception("[embedding_image] error")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/ai/embedding/text-multimodal")
async def embedding_text_multimodal(request: TextMultimodalEmbeddingRequest):
    """
    文字跨模态向量化接口

    用 multimodal-embedding-v1 模型将文字转为向量，
    该向量和图片向量在同一空间，可用于文字搜图片。

    注意：这不是 text-embedding-v4（那个用于纯文字检索），
    这是跨模态模型，专门用于文字↔图片的跨模态检索。
    """
    try:
        service = get_image_embedding()
        # multimodal-embedding-v1 的 input 格式支持 {"text": "xxx"}
        # 但现有 image_embedding.py 只支持 {"image": url} 格式
        # 所以这里直接调 API，用 text 输入格式
        import httpx
        headers = {
            "Authorization": f"Bearer {service.api_key}",
            "Content-Type": "application/json"
        }
        params = {
            "model": service.model,
            "input": [{"text": request.text}]
        }
        response = await service.client.post(
            f"{service.api_base}/embeddings",
            headers=headers,
            json=params
        )
        response.raise_for_status()
        result = response.json()

        if "data" not in result or not result["data"]:
            raise ValueError(f"Unexpected API response: {result}")

        vector = result["data"][0]["embedding"]
        return {
            "vector": vector,
            "dimension": len(vector)
        }
    except Exception as e:
        logger.exception("[embedding_text_multimodal] error")
        raise HTTPException(status_code=500, detail=str(e))
```

注意在文件顶部的 import 区域添加 `from typing import List`（如果还没有的话），以及 `from embeddings.image_embedding import get_image_embedding`。

- [ ] **Step 2: 验证接口启动无报错**

Run: `cd D:\py\daima\FixAgent && python -c "from api.main import app; print('OK')"`
Expected: OK

---

### Task 2: Python 端 — graph_service 新增图片向量检索

**Files:**
- Modify: `D:\py\daima\FixAgent\services\graph_service.py`

在 GraphService 类中新增方法，通过 Neo4j 的 imageEmbedding 向量索引检索实体，然后扩展到完整诊断路径。

- [ ] **Step 1: 在 GraphService 类中，在 find_solutions_by_fault 方法后新增以下方法**

```python
    # ==================== 图片向量检索 ====================

    def search_by_image_vector(
        self,
        entity_type: str,
        vector: List[float],
        limit: int = 5,
        min_score: float = 0.5
    ) -> List[Dict[str, Any]]:
        """
        通过图片向量检索 Neo4j 实体

        使用 Neo4j 向量索引在指定实体类型上做相似度检索。
        向量索引名约定为: {entity_type小写}_image_index

        Args:
            entity_type: 实体类型（Fault / Component / Device / Solution / CaseRecord）
            vector: 查询向量（来自 multimodal-embedding-v1）
            limit: 返回数量
            min_score: 最小相似度

        Returns:
            匹配的实体列表，含 id、name、score、imageUrls
        """
        index_name = f"{entity_type.lower()}_image_index"

        cypher = """
            CALL db.index.vector.queryNodes($indexName, $limit, $vector)
            YIELD node, score
            WHERE score >= $minScore
            RETURN node.id AS id,
                   node.name AS name,
                   node.image_urls AS imageUrls,
                   score
            ORDER BY score DESC
        """
        return self._execute_query(cypher, {
            "indexName": index_name,
            "limit": limit,
            "vector": vector,
            "minScore": min_score
        })

    def query_diagnosis_by_image_vector(
        self,
        vector: List[float],
        limit: int = 10,
        min_score: float = 0.5
    ) -> List[DiagnosisPath]:
        """
        通过图片/文字向量检索诊断路径

        先在 Fault 的 imageEmbedding 索引中找相似故障，
        再沿 Component ← CAUSES ← Fault → HAS_SOLUTION → Solution 扩展路径。

        Args:
            vector: 查询向量（图片向量或跨模态文字向量）
            limit: 返回数量
            min_score: 最小相似度

        Returns:
            诊断路径列表
        """
        cypher = """
            CALL db.index.vector.queryNodes('fault_image_index', $limit, $vector)
            YIELD node AS f, score
            WHERE score >= $minScore
            OPTIONAL MATCH (c:Component)-[:CAUSES]->(f)
            OPTIONAL MATCH (f)-[:HAS_SOLUTION]->(s:Solution)
            RETURN c.id AS componentId,
                   c.name AS componentName,
                   f.id AS faultId,
                   f.name AS faultName,
                   f.severity AS faultSeverity,
                   f.image_urls AS faultImageUrls,
                   s.id AS solutionId,
                   s.title AS solutionTitle,
                   s.estimated_time AS estimatedTime,
                   s.verified AS verified,
                   score AS imageScore
            ORDER BY score DESC, s.verified DESC
            LIMIT $limit
        """
        results = self._execute_query(cypher, {
            "limit": limit,
            "vector": vector,
            "minScore": min_score
        })

        paths = []
        for r in results:
            paths.append(DiagnosisPath(
                component_id=r.get("componentId"),
                component_name=r.get("componentName"),
                fault_id=r.get("faultId"),
                fault_name=r.get("faultName"),
                fault_severity=r.get("faultSeverity"),
                solution_id=r.get("solutionId"),
                solution_title=r.get("solutionTitle"),
                estimated_time=r.get("estimatedTime"),
                verified=r.get("verified")
            ))
        return paths
```

- [ ] **Step 2: 验证语法**

Run: `cd D:\py\daima\FixAgent && python -c "from services.graph_service import GraphService; print('OK')"`
Expected: OK

---

### Task 3: Python 端 — graph_query_tool 新增图片检索工具

**Files:**
- Modify: `D:\py\daima\FixAgent\tools\graph_query_tool.py`

新增一个 Tool，让 Agent RAG 可以通过图片 URL 检索诊断路径。

- [ ] **Step 1: 在文件末尾（单例部分之前）新增 GraphImageSearchTool 类**

```python
class GraphImageSearchTool(BaseTool):
    """
    图片检索诊断路径工具

    接收图片 URL 列表，通过跨模态向量在 Neo4j 图谱中检索匹配的故障，
    然后扩展到完整诊断路径：部件 → 故障 → 解决方案。
    """

    @property
    def name(self) -> str:
        return "graph_image_search"

    @property
    def description(self) -> str:
        return (
            "通过上传的图片从知识图谱中检索诊断路径。"
            "将图片转为向量，在故障图片索引中搜索相似故障，"
            "返回关联的部件和解决方案。"
            "适用场景：用户上传了故障现场照片，需要识别故障并给出维修方案。"
        )

    def get_parameters_schema(self) -> dict:
        return {
            "type": "object",
            "properties": {
                "image_urls": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "图片 URL 列表（MinIO 地址）"
                },
                "limit": {
                    "type": "integer",
                    "description": "返回结果数量上限，默认10",
                    "default": 10
                }
            },
            "required": ["image_urls"]
        }

    async def _execute(
        self,
        image_urls: list,
        limit: int = 10
    ) -> dict:
        try:
            from embeddings.image_embedding import get_image_embedding

            # 1. 图片转向量
            embedding_service = get_image_embedding()
            vectors = await embedding_service.embed_batch(image_urls)

            # 2. 取所有图片向量的平均值作为查询向量
            if not vectors:
                return {"paths": [], "message": "图片向量化失败"}

            avg_vector = [
                sum(v[i] for v in vectors) / len(vectors)
                for i in range(len(vectors[0]))
            ]

            # 3. 向量检索诊断路径
            graph = get_graph_service()
            paths = graph.query_diagnosis_by_image_vector(
                vector=avg_vector,
                limit=limit,
                min_score=0.5
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
                "image_count": len(image_urls),
                "paths_found": len(formatted),
                "paths": formatted
            }

        except Exception as e:
            raise ToolException(
                code="GRAPH_IMAGE_SEARCH_FAILED",
                message=f"图片检索诊断路径失败: {e}"
            )
```

- [ ] **Step 2: 在文件末尾的单例部分新增**

```python
_image_search_tool: Optional[GraphImageSearchTool] = None


def get_graph_image_search_tool() -> GraphImageSearchTool:
    """获取图片检索诊断路径工具单例"""
    global _image_search_tool
    if _image_search_tool is None:
        _image_search_tool = GraphImageSearchTool()
    return _image_search_tool
```

---

### Task 4: Java 端 — 5 个实体添加 imageUrls + imageEmbedding

**Files:**
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\entity\Device.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\entity\Component.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\entity\Fault.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\entity\Solution.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\entity\CaseRecord.java`

每个实体新增两个字段：

- [ ] **Step 1: 为 5 个实体各添加以下两个字段**

```java
    @Property("image_urls")
    private List<String> imageUrls;

    @Property("image_embedding")
    private List<Double> imageEmbedding;
```

Device.java 和 CaseRecord.java 需要额外 `import java.util.List;`（如果还没有的话）。
Component.java 和 Fault.java 已有 `List` import。
Solution.java 需要 `import java.util.List;`。

- [ ] **Step 2: 编译验证**

Run: `cd D:\javaWeb\daima\weixiu && mvn compile -q`
Expected: 无输出（编译成功）

---

### Task 5: Java 端 — DTO 和 VO 添加 imageUrls

**Files:**
- Modify: `DeviceDTO.java`, `ComponentDTO.java`, `FaultDTO.java`, `SolutionDTO.java`, `CaseRecordDTO.java`
- Modify: `DeviceVO.java`, `ComponentVO.java`, `FaultVO.java`, `SolutionVO.java`
- Modify: `DiagnosisPathVO.java`

- [ ] **Step 1: 5 个 DTO 各添加**

```java
    private List<String> imageUrls;
```

需要为每个 DTO 添加 `import java.util.List;`（如果还没有的话）。

- [ ] **Step 2: 4 个 VO 各添加（CaseRecord 没有单独的 VO 可跳过）**

```java
    private List<String> imageUrls;
```

- [ ] **Step 3: DiagnosisPathVO 添加 imageUrls**

在 `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\pojo\vo\DiagnosisPathVO.java` 中添加：

```java
    private List<String> faultImageUrls;
    private List<String> componentImageUrls;
```

- [ ] **Step 4: 编译验证**

Run: `cd D:\javaWeb\daima\weixiu && mvn compile -q`
Expected: 无输出

---

### Task 6: Java 端 — ImageEmbeddingUtils 工具类

**Files:**
- Create: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\utils\ImageEmbeddingUtils.java`

这个工具类调用 Python 的 `/ai/embedding/image` 接口拿图片向量。

- [ ] **Step 1: 新建 ImageEmbeddingUtils.java**

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
import java.util.List;
import java.util.Map;

/**
 * 图片向量化工具
 *
 * 调用 Python 端的 /ai/embedding/image 接口，
 * 将多张图片 URL 转为向量，取平均值返回。
 *
 * 向量由 multimodal-embedding-v1 模型生成，
 * 和文字跨模态向量在同一空间，支持文字搜图片。
 */
@Component
@Slf4j
public class ImageEmbeddingUtils {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    public ImageEmbeddingUtils(
            @Value("${ai.python-service-url:http://localhost:5000}") String pythonServiceUrl,
            ObjectMapper objectMapper
    ) {
        this.webClient = WebClient.create(pythonServiceUrl);
        this.objectMapper = objectMapper;
    }

    /**
     * 将多张图片 URL 转为一个平均向量
     *
     * 流程：发送图片 URL 列表到 Python → 收到每张图的向量 → 取平均值
     *
     * @param imageUrls 图片 URL 列表（MinIO 地址）
     * @return 平均向量，如果图片列表为空或调用失败返回 null
     */
    public List<Double> getImageEmbedding(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return null;
        }

        try {
            String response = webClient.post()
                    .uri("/ai/embedding/image")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(objectMapper.writeValueAsString(
                            Map.of("image_urls", imageUrls)
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);

            JsonNode root = objectMapper.readTree(response);
            JsonNode vectorsNode = root.get("vectors");

            if (vectorsNode == null || !vectorsNode.isArray() || vectorsNode.isEmpty()) {
                log.warn("图片向量化返回为空");
                return null;
            }

            // 计算所有向量的平均值
            int dimension = vectorsNode.get(0).size();
            int vectorCount = vectorsNode.size();
            List<Double> avgVector = new ArrayList<>(dimension);

            for (int d = 0; d < dimension; d++) {
                double sum = 0;
                for (int v = 0; v < vectorCount; v++) {
                    sum += vectorsNode.get(v).get(d).asDouble();
                }
                avgVector.add(sum / vectorCount);
            }

            return avgVector;

        } catch (Exception e) {
            log.error("图片向量化失败: {}", e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd D:\javaWeb\daima\weixiu && mvn compile -q`
Expected: 无输出

---

### Task 7: Java 端 — Service 层在 save/update 时生成图片向量

**Files:**
- Modify: `DeviceServiceImpl.java`
- Modify: `ComponentServiceImpl.java`
- Modify: `FaultServiceImpl.java`
- Modify: `SolutionServiceImpl.java`
- Modify: `CaseRecordServiceImpl.java`

在每个 ServiceImpl 的 save() 和 update() 方法中，如果 DTO 带了 imageUrls，就调 ImageEmbeddingUtils 拿图片向量。

- [ ] **Step 1: DeviceServiceImpl — 注入 ImageEmbeddingUtils 并修改 save/update**

在构造器注入中添加 `ImageEmbeddingUtils`（因为用了 `@AllArgsConstructor` + `final`）：

```java
private final ImageEmbeddingUtils imageEmbeddingUtils;
```

修改 save()：
```java
@Override
@Transactional
public Device save(DeviceDTO deviceDTO) {
    Device device = toEntity(deviceDTO);
    device.setId(UUID.randomUUID().toString());
    // 图片向量化
    if (device.getImageUrls() != null && !device.getImageUrls().isEmpty()) {
        device.setImageEmbedding(imageEmbeddingUtils.getImageEmbedding(device.getImageUrls()));
    }
    return deviceRepository.save(device);
}
```

修改 update()：
```java
@Override
@Transactional
public Device update(DeviceDTO deviceDTO) {
    Device device = toEntity(deviceDTO);
    if (device.getImageUrls() != null && !device.getImageUrls().isEmpty()) {
        device.setImageEmbedding(imageEmbeddingUtils.getImageEmbedding(device.getImageUrls()));
    }
    return deviceRepository.save(device);
}
```

- [ ] **Step 2: ComponentServiceImpl — 同样模式**

添加 `private final ImageEmbeddingUtils imageEmbeddingUtils;`

在 save() 和 update() 中 `component.setEmbedding(embedding);` 之后添加：
```java
if (component.getImageUrls() != null && !component.getImageUrls().isEmpty()) {
    component.setImageEmbedding(imageEmbeddingUtils.getImageEmbedding(component.getImageUrls()));
}
```

- [ ] **Step 3: FaultServiceImpl — 同样模式**

添加 `private final ImageEmbeddingUtils imageEmbeddingUtils;`

在 save() 和 update() 中 `fault.setEmbedding(embedding);` 之后添加：
```java
if (fault.getImageUrls() != null && !fault.getImageUrls().isEmpty()) {
    fault.setImageEmbedding(imageEmbeddingUtils.getImageEmbedding(fault.getImageUrls()));
}
```

- [ ] **Step 4: SolutionServiceImpl 和 CaseRecordServiceImpl — 同样模式**

这两个 ServiceImpl 需要先读取确认当前 save/update 逻辑，然后添加 imageEmbeddingUtils 注入和向量化调用。模式与上面完全一致。

- [ ] **Step 5: 编译验证**

Run: `cd D:\javaWeb\daima\weixiu && mvn compile -q`
Expected: 无输出

---

### Task 8: Java 端 — Repository 新增图片向量检索查询

**Files:**
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\repository\FaultRepository.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\repository\ComponentRepository.java`

- [ ] **Step 1: FaultRepository 新增图片向量检索方法**

```java
    /**
     * 通过图片向量检索最相似的故障
     */
    @Query("""
        CALL db.index.vector.queryNodes('fault_image_index', $limit, $embedding)
        YIELD node AS f, score
        WHERE score >= $minScore
        RETURN f.id AS id,
               f.name AS name,
               f.description AS description,
               f.category AS category,
               f.severity AS severity,
               f.image_urls AS imageUrls,
               score
        ORDER BY score DESC
        """)
    List<FaultVO> getFaultsByImageEmbedding(
        @Param("embedding") List<Double> embedding,
        @Param("limit") long limit,
        @Param("minScore") double minScore
    );
```

- [ ] **Step 2: ComponentRepository 新增图片向量检索方法**

```java
    /**
     * 通过图片向量检索最相似的部件
     */
    @Query("""
        CALL db.index.vector.queryNodes('component_image_index', $limit, $embedding)
        YIELD node AS c, score
        WHERE score >= $minScore
        RETURN c.id AS id,
               c.name AS name,
               c.part_number AS partNumber,
               c.specification AS specification,
               c.supplier AS supplier,
               c.lifecycle AS lifecycle,
               c.unit_price AS unitPrice,
               c.image_urls AS imageUrls,
               score
        ORDER BY score DESC
        """)
    List<ComponentVO> getComponentByImageEmbedding(
        @Param("embedding") List<Double> embedding,
        @Param("limit") Long limit,
        @Param("minScore") Double minScore
    );
```

- [ ] **Step 3: 编译验证**

Run: `cd D:\javaWeb\daima\weixiu && mvn compile -q`
Expected: 无输出

---

### Task 9: Java 端 — GraphQueryService 新增图片检索诊断路径

**Files:**
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\service\GraphQueryService.java`
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\service\impl\GraphQueryServiceImpl.java`
- Create: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\pojo\query\ImageSearchQuery.java`

- [ ] **Step 1: 新建 ImageSearchQuery**

```java
package ai.weixiu.pojo.query;

import lombok.Data;
import java.util.List;

@Data
public class ImageSearchQuery {
    /** 图片 URL 列表（MinIO 地址） */
    private List<String> imageUrls;
    /** 返回数量，默认 10 */
    private int limit = 10;
    /** 最小相似度，默认 0.5 */
    private double minScore = 0.5;
}
```

- [ ] **Step 2: GraphQueryService 接口新增方法**

```java
PageResult<DiagnosisPathVO> findDiagnosisPathsByImage(ImageSearchQuery query);
```

- [ ] **Step 3: GraphQueryServiceImpl 实现**

注入 `ImageEmbeddingUtils`（添加 final 字段即可，`@AllArgsConstructor` 自动注入）。

新增方法：

```java
    @Override
    public PageResult<DiagnosisPathVO> findDiagnosisPathsByImage(ImageSearchQuery query) {
        // 1. 调 Python 拿图片向量
        List<Double> imageVector = imageEmbeddingUtils.getImageEmbedding(query.getImageUrls());
        if (imageVector == null || imageVector.isEmpty()) {
            return emptyResult(0, query.getLimit());
        }

        // 2. 用图片向量在 Fault 的 imageEmbedding 索引中检索
        List<DiagnosisPathVO> records = neo4jClient.query("""
                CALL db.index.vector.queryNodes('fault_image_index', $limit, $vector)
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
                .bind(imageVector).to("vector")
                .bind(query.getLimit()).to("limit")
                .bind(query.getMinScore()).to("minScore")
                .fetchAs(DiagnosisPathVO.class)
                .mappedBy((ctx, record) -> {
                    DiagnosisPathVO vo = mapDiagnosisPath(record);
                    vo.setFaultScore(record.get("score").isNull() ? null : record.get("score").asDouble());
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

- [ ] **Step 4: 编译验证**

Run: `cd D:\javaWeb\daima\weixiu && mvn compile -q`
Expected: 无输出

---

### Task 10: Java 端 — PathController 新增图片检索接口

**Files:**
- Modify: `D:\javaWeb\daima\weixiu\src\main\java\ai\weixiu\controller\PathController.java`

- [ ] **Step 1: 在 PathController 中新增接口**

```java
    @PostMapping("/image-search")
    @Operation(summary = "通过图片检索诊断路径")
    public Result<PageResult<DiagnosisPathVO>> searchByImage(@RequestBody ImageSearchQuery query) {
        return Result.success(graphQueryService.findDiagnosisPathsByImage(query));
    }
```

确保 import 了 `ImageSearchQuery` 和已有的 `DiagnosisPathVO`、`PageResult`。

- [ ] **Step 2: 编译验证**

Run: `cd D:\javaWeb\daima\weixiu && mvn compile -q`
Expected: 无输出

---

### Task 11: Neo4j 向量索引初始化

需要在 Neo4j 中创建 5 个图片向量索引。这些索引是 Neo4j 使用 `db.index.vector.queryNodes` 的前提。

- [ ] **Step 1: 在 Neo4j Browser 或 cypher-shell 中执行以下 Cypher**

向量维度取决于 multimodal-embedding-v1 模型（通常 1024），需要确认。可以先调一次 `/ai/embedding/image` 看返回的 dimension 字段。

```cypher
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
```

- [ ] **Step 2: 验证索引创建成功**

```cypher
SHOW INDEXES WHERE type = 'VECTOR';
```

Expected: 看到 5 个新建的 `_image_index` + 已有的 `component_embedding_index` 和 `fault_embedding_index`（共 7 个向量索引）。

---

### Task 12: 端到端验证

- [ ] **Step 1: 启动 Python 服务，验证新接口**

```bash
# 测试图片向量化
curl -X POST http://localhost:5000/ai/embedding/image \
  -H "Content-Type: application/json" \
  -d '{"image_urls": ["你的MinIO图片URL"]}'

# 测试文字跨模态向量化
curl -X POST http://localhost:5000/ai/embedding/text-multimodal \
  -H "Content-Type: application/json" \
  -d '{"text": "电机轴承磨损"}'
```

Expected: 返回 vectors/vector 数组和 dimension 字段。

- [ ] **Step 2: 启动 Java 服务，验证图片检索**

先通过已有的 save 接口创建一个带图片的 Fault：

```bash
curl -X POST http://localhost:8080/weixiu/fault/save \
  -H "Content-Type: application/json" \
  -d '{
    "name": "电机轴承磨损",
    "description": "轴承表面出现磨损痕迹，运行时有异响",
    "severity": "严重",
    "category": "机械",
    "imageUrls": ["你的MinIO图片URL"]
  }'
```

然后通过图片检索：

```bash
curl -X POST http://localhost:8080/weixiu/path/image-search \
  -H "Content-Type: application/json" \
  -d '{
    "imageUrls": ["另一张类似故障的图片URL"],
    "limit": 5,
    "minScore": 0.5
  }'
```

Expected: 返回包含上面创建的故障的诊断路径。
