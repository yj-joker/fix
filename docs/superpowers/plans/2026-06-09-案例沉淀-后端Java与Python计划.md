# 案例/经验知识沉淀 — 后端(Java + Python)实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development 或 superpowers:executing-plans 逐任务实现。步骤用 `- [ ]` 勾选跟踪。
> **本项目无自动化测试框架**，验证步骤统一用「编译 + curl 打接口 + 看 Neo4j/MySQL」这条真实反馈回路。

**Goal:** 让一线人员从已完成检修任务沉淀出"案例/经验"，经合规闸门 + 老师傅 + 管理员双审后入 Neo4j(CaseRecord)，并被 RAG 向量召回。

**Architecture:** 统一主干「输入通道 → AI 起草(含 Basic Reflection) → 合规 LLM 闸门 → 老师傅改 → 管理员审 → 向量化+尽力连边」。Python(FixAgent) 只产 JSON/算文本，Neo4j 落库/连边/检索收口在 Java。检索走向量(`case_record_multimodal_index` 已存在)，案例永不悬空。

**Tech Stack:** Spring Boot 3.5 + Spring Data Neo4j + WebClient；FastAPI + DashScope(qwen)；现有 `MultimodalEmbeddingUtils`、`GraphQueryServiceImpl`、`CaseRecord` 节点、`RelationServiceImpl(CASE_RECORD_RECORDED_FAULT)`。

参考 spec：`docs/superpowers/specs/2026-06-09-案例经验知识沉淀-design.md`

---

## 文件结构(期1)

**Python(FixAgent)**
- 修改 `schemas/request.py`、`schemas/response.py`：新增 case draft / compliance 的请求响应模型。
- 创建 `agents/case_agent.py`：案例起草 Agent(含 Basic Reflection)+ 合规判定。
- 修改 `api/main.py`：新增 `POST /ai/case/draft`、`POST /ai/case/compliance`。

**Java(weixiu)**
- 修改 `entity/CaseRecord.java`：加字段。
- 修改 `pojo/dto/CaseRecordDTO.java`、新增 `pojo/vo/CaseDraftVO.java`、`pojo/vo/CaseRecordReviewVO.java`。
- 修改 `service/CaseRecordService.java` + `impl/CaseRecordServiceImpl.java`：draftFromTask/submit/pending/approve/reject/mine + `getByEmbedding`。
- 修改 `controller/CaseRecordController.java`：新增端点。
- 修改 `service/impl/GraphQueryServiceImpl.java`：path/search 并入案例向量召回。
- 修改 `pojo/vo/DiagnosisPathVO.java`(或返回包装)：增加 `cases` 字段。

---

## 期 1 · 主干 + 检索接通

### Task 1: CaseRecord 实体加字段

**Files:** Modify `src/main/java/ai/weixiu/entity/CaseRecord.java`

- [ ] **Step 1: 加 @Property 字段**

在现有字段后追加：
```java
@Property("status")          private String status;            // pending/approved/rejected
@Property("source_type")     private String sourceType;        // task/file/note_photo/voice
@Property("source_task_id")  private Long sourceTaskId;
@Property("source_file_url") private String sourceFileUrl;
@Property("submitted_by_id") private Long submittedById;
@Property("reviewed_by_id")  private Long reviewedById;
@Property("reviewed_at")     private LocalDateTime reviewedAt;
@Property("review_comment")  private String reviewComment;
@Property("compliance_reason") private String complianceReason;
@Property("device_id")       private String deviceId;          // 尽力而为
@Property("fault_name")      private String faultName;         // 尽力匹配 Fault 用
```

- [ ] **Step 2: 编译**

Run: `mvn -o compile -f pom.xml | grep -E 'BUILD'`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/ai/weixiu/entity/CaseRecord.java
git commit -m "feat(case): CaseRecord 增加审核/来源/锚定字段"
```

---

### Task 2: Python 案例起草 Agent(含 Basic Reflection)

**Files:** Create `agents/case_agent.py`；Modify `schemas/request.py`、`schemas/response.py`

- [ ] **Step 1: 请求/响应模型**

`schemas/request.py` 追加：
```python
class CaseDraftRequest(BaseModel):
    source_type: str = "task"            # task/file/note_photo/voice
    task_context: Optional[str] = None   # 任务拼装文本
    raw_text: Optional[str] = None       # 文件/OCR/语音转写文本
    images: Optional[List[str]] = None

class CaseComplianceRequest(BaseModel):
    text: str
```
`schemas/response.py` 追加：
```python
class CaseDraftResponse(BaseModel):
    title: str = ""
    summary: str = ""
    diagnosis: str = ""
    resolution: str = ""
    result: str = ""
    experience_summary: str = ""
    tags: str = ""
    downtime: Optional[int] = None
    cost: Optional[float] = None

class CaseComplianceResponse(BaseModel):
    compliant: bool
    relevance: bool
    legality: bool
    reason: str = ""
```

- [ ] **Step 2: case_agent.py — 起草 + Reflection + 合规**

```python
# agents/case_agent.py
import json, logging
from services.llm_service import get_llm_service
logger = logging.getLogger(__name__)

_DRAFT_SYS = """你是设备检修案例整理助手。把给定材料整理成结构化检修案例，只输出 JSON：
{"title","summary","diagnosis","resolution","result","experience_summary","tags","downtime","cost"}
要求：忠于材料，不编造；experience_summary 提炼可复用经验；tags 用逗号分隔。"""

_REFLECT_SYS = """检查上一版案例 JSON 是否有：编造材料里没有的事实、遗漏关键步骤、字段错填。
若有问题输出修正后的完整 JSON；若没有问题，原样输出该 JSON。只输出 JSON。"""

_COMPLY_SYS = """你是内容合规审核员。判断文本是否可纳入设备检修知识库，只输出 JSON：
{"relevance":bool,"legality":bool,"reason":str}
relevance=是否属于设备检修/维修经验；legality=是否不含违法/有害/敏感/人身攻击。reason 说明拦截原因（中文）。"""

async def draft_case(req) -> dict:
    llm = get_llm_service()
    material = req.task_context or req.raw_text or ""
    # 1) 起草
    r1 = await llm.chat(messages=[{"role":"system","content":_DRAFT_SYS},
                                  {"role":"user","content":material}],
                        response_format={"type":"json_object"})
    draft = _safe_json(r1["content"])
    # 2) Basic Reflection 一轮
    r2 = await llm.chat(messages=[{"role":"system","content":_REFLECT_SYS},
                                  {"role":"user","content":json.dumps(draft, ensure_ascii=False)}],
                        response_format={"type":"json_object"})
    refined = _safe_json(r2["content"]) or draft
    return refined

async def check_compliance(text: str) -> dict:
    llm = get_llm_service()
    r = await llm.chat(messages=[{"role":"system","content":_COMPLY_SYS},
                                 {"role":"user","content":text[:4000]}],
                       response_format={"type":"json_object"})
    d = _safe_json(r["content"]) or {}
    relevance = bool(d.get("relevance", False))
    legality = bool(d.get("legality", False))
    return {"compliant": relevance and legality, "relevance": relevance,
            "legality": legality, "reason": d.get("reason","")}

def _safe_json(s: str):
    try:
        import re
        m = re.search(r"\{.*\}", s or "", re.S)
        return json.loads(m.group(0)) if m else None
    except Exception:
        return None
```

- [ ] **Step 3: main.py 两个端点**

```python
from agents.case_agent import draft_case, check_compliance
from schemas.request import CaseDraftRequest, CaseComplianceRequest
from schemas.response import CaseDraftResponse, CaseComplianceResponse

@app.post("/ai/case/draft", response_model=CaseDraftResponse)
async def ai_case_draft(req: CaseDraftRequest):
    d = await draft_case(req)
    return CaseDraftResponse(**{k: d.get(k) for k in CaseDraftResponse.model_fields if k in d})

@app.post("/ai/case/compliance", response_model=CaseComplianceResponse)
async def ai_case_compliance(req: CaseComplianceRequest):
    return CaseComplianceResponse(**await check_compliance(req.text))
```

- [ ] **Step 4: 验证(直接 curl)**

```bash
# 起草
printf '{"source_type":"task","task_context":"设备:摩托车发动机 故障:启动困难 步骤:检查火花塞、清积碳 结果:已修复"}' > /tmp/d.json
curl -s -m 60 -X POST http://127.0.0.1:8000/ai/case/draft -H "Content-Type: application/json" --data-binary @/tmp/d.json
# 合规(应 compliant=false relevance=false)
printf '{"text":"今天天气不错适合钓鱼"}' > /tmp/c.json
curl -s -m 30 -X POST http://127.0.0.1:8000/ai/case/compliance -H "Content-Type: application/json" --data-binary @/tmp/c.json
```
Expected: draft 返回结构化 JSON；钓鱼文本 `compliant:false,relevance:false`。

- [ ] **Step 5: Commit**(Python 端，用户手动管理 Python 仓库提交)

---

### Task 3: Java — draftFromTask(组装任务上下文 → 调 Python 起草)

**Files:** Modify `service/CaseRecordService.java`、`impl/CaseRecordServiceImpl.java`；Create `pojo/vo/CaseDraftVO.java`

- [ ] **Step 1: CaseDraftVO**（与 Python 返回对齐 + 带入 deviceId/faultName/sourceTaskId）
```java
@Data public class CaseDraftVO {
    private Long sourceTaskId; private String deviceId; private String deviceName;
    private String faultName; private String title; private String summary;
    private String diagnosis; private String resolution; private String result;
    private String experienceSummary; private String tags;
    private Integer downtime; private Double cost; private List<String> imageUrls;
}
```

- [ ] **Step 2: draftFromTask(taskId)**

在 `CaseRecordServiceImpl` 注入 `MaintenanceTaskMapper`、`TaskStepRecordMapper`、`WebClient`、`ObjectMapper`。实现：
1. 查任务 + 步骤，校验 status=CLOSED；
2. 幂等：若该 taskId 已有 pending/approved CaseRecord → 抛业务异常"该任务已沉淀过案例"；
3. 拼装 `task_context`(故障描述 + 每步 title/content/note + 设备名)；
4. `webClient.post("/ai/case/draft", {source_type:"task", task_context, images: task.reportImages 转base64})` 取草稿；
5. 组装 `CaseDraftVO`(带 task.deviceId/deviceName、faultName=task.faultDescription、imageUrls=task.reportImages)，返回(不落库)。

- [ ] **Step 3: 编译**

Run: `mvn -o compile -f pom.xml | grep BUILD` → `BUILD SUCCESS`

- [ ] **Step 4: Commit**
```bash
git add -A && git commit -m "feat(case): draftFromTask 组装任务上下文调用AI起草"
```

---

### Task 4: Java — submit(合规闸门 → 落 pending)

**Files:** Modify `pojo/dto/CaseRecordDTO.java`、`impl/CaseRecordServiceImpl.java`

- [ ] **Step 1: CaseRecordDTO 补字段**：sourceType/sourceTaskId/sourceFileUrl/deviceId/faultName + 草稿全字段(title…cost) + imageUrls。

- [ ] **Step 2: submit(dto)**
1. 拼合规文本 `title+summary+diagnosis+resolution+experienceSummary`；
2. 调 `webClient.post("/ai/case/compliance",{text})`；
3. 若 `compliant=false` → 抛业务异常，message=合规 reason(前端展示，拦截提交)；
4. 否则建 CaseRecord：status=pending、submittedById=当前用户、sourceType/sourceTaskId/deviceId/faultName/imageUrls、complianceReason=reason，`caseRecordRepository.save`。

- [ ] **Step 3: 编译 + Commit**
```bash
mvn -o compile -f pom.xml | grep BUILD
git add -A && git commit -m "feat(case): submit 合规闸门拦截+落待审"
```

---

### Task 5: Java — pending/approve/reject/mine

**Files:** Modify `service/CaseRecordService.java`、`impl/CaseRecordServiceImpl.java`、`mapper/Repository`

- [ ] **Step 1: pending(page,size)**：`MATCH (c:CaseRecord {status:'pending'}) RETURN c ORDER BY c.recorded_at DESC SKIP $skip LIMIT $size`(Neo4jClient 或 Repository 查询)。返回列表 + total。

- [ ] **Step 2: approve(id, dto)** — 核心
1. 取 CaseRecord(pending)；用 dto 覆盖管理员编辑后的字段(标注/修正)；
2. **向量化(强制)**：`multimodalEmbeddingUtils` 对 `summary+experienceSummary`(+imageUrls) 生成 embedding，setMultimodalEmbedding；**失败抛异常(阻塞)**，前端提示可重试；
3. **尽力连边(非阻塞，try/catch 包住)**：
   - deviceId 非空 → `MATCH (c:CaseRecord{id}),(d:Device{id:$deviceId}) MERGE (d)-[:OWNS]->(c)`(或 Device-[:HAS_CASE]->，按你图谱关系命名，下同)；
   - faultName 非空 → 用 `faultService.getFaultByEmbedding(faultName,1,minScore)` 取最相似已有 Fault，命中则 `RelationServiceImpl.create(CASE_RECORD_RECORDED_FAULT, caseId, faultId)`；不命中不连、不新建 Fault；
4. set status=approved、reviewedById/At；save。

- [ ] **Step 3: reject(id, comment)**：set status=rejected、reviewComment、reviewedById/At。

- [ ] **Step 4: mine(userId,page,size)**：`MATCH (c:CaseRecord{submitted_by_id:$uid}) RETURN c ORDER BY recorded_at DESC`。

- [ ] **Step 5: 编译 + Commit**
```bash
mvn -o compile -f pom.xml | grep BUILD
git add -A && git commit -m "feat(case): pending/approve(向量化+尽力连边)/reject/mine"
```

---

### Task 6: Java — 控制器端点

**Files:** Modify `controller/CaseRecordController.java`

- [ ] **Step 1: 加端点**
```java
@PostMapping("/draft-from-task/{taskId}")
public Result<CaseDraftVO> draftFromTask(@PathVariable Long taskId){ return Result.success(caseRecordService.draftFromTask(taskId)); }
@PostMapping("/submit")
public Result<Void> submit(@RequestBody CaseRecordDTO dto){ caseRecordService.submit(dto); return Result.success(); }
@GetMapping("/pending")
public Result<PageResult<CaseRecordVO>> pending(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size){ return Result.success(caseRecordService.pending(page,size)); }
@PostMapping("/{id}/approve")
public Result<Void> approve(@PathVariable String id,@RequestBody CaseRecordDTO dto){ caseRecordService.approve(id,dto); return Result.success(); }
@PostMapping("/{id}/reject")
public Result<Void> reject(@PathVariable String id,@RequestParam String comment){ caseRecordService.reject(id,comment); return Result.success(); }
@GetMapping("/mine")
public Result<PageResult<CaseRecordVO>> mine(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size){ return Result.success(caseRecordService.mine(page,size)); }
```

- [ ] **Step 2: 编译 + 端到端验证**

Run: `mvn -o compile -f pom.xml | grep BUILD`；重启 Java；用一个 CLOSED 任务调 `/draft-from-task/{id}` → `/submit`(放一条合规的)→ `/pending` → `/{id}/approve`，到 Neo4j 确认 `MATCH (c:CaseRecord{status:'approved'}) RETURN c.multimodal_embedding IS NOT NULL` 为 true。
- [ ] **Step 3: Commit**
```bash
git add -A && git commit -m "feat(case): 案例记录审核流端点"
```

---

### Task 7: 检索接通 — path/search 并入案例向量召回(期1 命脉)

**Files:** Modify `service/impl/GraphQueryServiceImpl.java`、`service/CaseRecordService.java`(+impl)、返回 VO

- [ ] **Step 1: CaseRecordService.getByEmbedding(desc, limit, minScore)**

用 Neo4j 向量索引召回 approved 案例：
```cypher
CALL db.index.vector.queryNodes('case_record_multimodal_index', $k, $vec)
YIELD node, score WHERE node.status='approved' AND score >= $minScore
RETURN node, score
```
`$vec` 由 `embeddingUtils.getEmbedding(desc)` 生成。返回 `List<CaseRecordVO>`(含 score)。

- [ ] **Step 2: 在 GraphQueryServiceImpl.search 末尾并入**

在已有 fault/component 召回之后，新增：当 `hasFaultDesc` 时调 `caseRecordService.getByEmbedding(query.getFaultDescription(), searchLimit, minScore)`，把结果放进返回结构的 `cases` 字段(在 DiagnosisPath 返回 VO 上新增 `List<CaseRecordVO> cases`)。

- [ ] **Step 3: graph_java_tool 消费(Python)**

`tools/graph_java_tool.py`：解析返回 `data.cases`，拼进给 LLM 的证据文本(如「【相关案例】标题/经验摘要」)。

- [ ] **Step 4: 编译 + 关键验收**

重启 Java/Python；先沉淀并 approve 一条"启动困难"案例；再用 `graph_java_tool`(或检修助手问"发动机启动困难怎么办")验证返回证据里**出现该案例**。
Expected: LLM 证据中可见沉淀的案例经验。
- [ ] **Step 5: Commit**
```bash
git add -A && git commit -m "feat(case): 案例向量召回并入path/search，RAG出口接通"
```

---

### Task 8: 只读端点 — 故障下的案例(供前端图谱展开)

**Files:** Modify `controller/FaultController.java`、`service/impl/CaseRecordServiceImpl.java`

- [ ] **Step 1: CaseRecordService.getCasesByFault(faultId,page,size)**
```cypher
MATCH (cr:CaseRecord)-[:RECORDED]->(f:Fault {id:$faultId})
WHERE cr.status='approved'
RETURN cr ORDER BY cr.recorded_at DESC SKIP $skip LIMIT $size
```
返回 `PageResult<CaseRecordVO>`。

- [ ] **Step 2: FaultController 加端点**
```java
@PostMapping("/cases")
public Result<PageResult<CaseRecordVO>> cases(@RequestBody Map<String,Object> body){
    String faultId = String.valueOf(body.get("faultId"));
    int page = body.get("page")==null?0:((Number)body.get("page")).intValue();
    int size = body.get("size")==null?50:((Number)body.get("size")).intValue();
    return Result.success(caseRecordService.getCasesByFault(faultId, page, size));
}
```
(端点路径与现有 `/weixiu/fault/solutions` 同级，前端 `getFaultCases` 打 `/weixiu/fault/cases`。)

- [ ] **Step 3: 编译 + Commit**
```bash
mvn -o compile -f pom.xml | grep BUILD
git add -A && git commit -m "feat(case): 故障下案例只读分页端点(图谱展开用)"
```

---

## 期 2 · 文件 / 笔记拍照通道(任务级 outline)

- [ ] T1 Python `/ai/case/extract`：入参文件(txt/pdf/word)或图片 → 抽文本/VLM OCR → 复用 `draft_case`(source_type=file/note_photo)。复用 FixAgent 既有 PDF 解析。
- [ ] T2 Java `POST /case-record/draft-from-file`：multipart 上传 → 存 MinIO(sourceFileUrl)→ 调 `/ai/case/extract` → 返回 CaseDraftVO。
- [ ] T3 之后完全复用期1 submit/审核/检索链路(零改动)。
- [ ] T4 验收：传一份 pdf / 一张笔记照片 → 出草稿 → 审核入图 → 可被召回。

## 期 3 · 语音口述通道(任务级 outline)

- [ ] T1 复用现有 ASR(`/api/asr/transcribe` 或百度 `VoiceToTextUtils`)→ 文本。
- [ ] T2 Java `POST /case-record/draft-from-voice`(multipart 音频)→ ASR → `/ai/case/draft`(source_type=voice)→ CaseDraftVO。
- [ ] T3 复用期1 主干。
- [ ] T4 验收：口述一段经验 → 转写 → 草稿 → 审核入图 → 可召回。

---

## Self-Review 结论
- 覆盖 spec §4(Python 两端点 含 Reflection/合规)、§5(Java 全端点 + approve 向量化阻塞 + 尽力连边)、§5.5(检索并入 path/search ← Task 7)、§7 期1 验收(Task 7 Step4 即"提问能召回")。
- 锚定尽力而为：approve 连边全 try/catch 不阻塞，仅向量化强制——与 spec 一致。
- 期2/3 为 outline：它们只加"通道适配器"，主干/审核/检索复用期1，待期1 落地后再细化。
