package ai.weixiu.service.impl;

import ai.weixiu.entity.CaseRecord;
import ai.weixiu.entity.MaintenanceTask;
import ai.weixiu.entity.TaskStepRecord;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.exceprion.TaskStateException;
import ai.weixiu.mapper.MaintenanceTaskMapper;
import ai.weixiu.mapper.TaskStepRecordMapper;
import ai.weixiu.pojo.dto.CaseRecordDTO;
import ai.weixiu.pojo.vo.CaseDraftVO;
import ai.weixiu.repository.CaseRecordRepository;
import ai.weixiu.service.CaseRecordService;
import ai.weixiu.utils.BuildStringUtils;
import ai.weixiu.utils.MultimodalEmbeddingUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@AllArgsConstructor
public class CaseRecordServiceImpl implements CaseRecordService {

    private final CaseRecordRepository caseRecordRepository;
    private final MultimodalEmbeddingUtils multimodalEmbeddingUtils;
    private final BuildStringUtils buildStringUtils;
    private final MaintenanceTaskMapper taskMapper;
    private final TaskStepRecordMapper stepMapper;
    private final Neo4jClient neo4jClient;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String notFoundMessage = "案例记录不存在";

    @Override
    @Transactional
    public CaseRecord save(CaseRecordDTO caseRecordDTO) {
        CaseRecord caseRecord = toEntity(caseRecordDTO);
        caseRecord.setId(UUID.randomUUID().toString());
        String embeddingText = buildStringUtils.buildCaseRecordEmbeddingText(caseRecord);
        caseRecord.setMultimodalEmbedding(
            multimodalEmbeddingUtils.getMultimodalEmbedding(embeddingText, caseRecord.getImageUrls())
        );
        return caseRecordRepository.save(caseRecord);
    }

    @Override
    public Optional<CaseRecord> findById(String id) {
        Optional<CaseRecord> caseRecord = caseRecordRepository.findById(id);
        if (!caseRecord.isPresent()) {
            throw new NotFoundException(notFoundMessage);
        }
        return caseRecord;
    }

    @Override
    public List<CaseRecord> findAll() {
        return caseRecordRepository.findAll();
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        caseRecordRepository.deleteById(id);
    }

    @Override
    @Transactional
    public CaseRecord update(CaseRecordDTO caseRecordDTO) {
        CaseRecord caseRecord = toEntity(caseRecordDTO);
        String embeddingText = buildStringUtils.buildCaseRecordEmbeddingText(caseRecord);
        caseRecord.setMultimodalEmbedding(
            multimodalEmbeddingUtils.getMultimodalEmbedding(embeddingText, caseRecord.getImageUrls())
        );
        return caseRecordRepository.save(caseRecord);
    }

    @Override
    public CaseDraftVO draftFromTask(Long taskId) {
        // 1. 任务存在性
        MaintenanceTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new NotFoundException("检修任务不存在: " + taskId);
        }
        // 2. 仅已关闭任务可沉淀
        if (!"CLOSED".equals(task.getStatus())) {
            throw new TaskStateException("只有已关闭的任务才能沉淀案例，当前状态: " + task.getStatus());
        }
        // 3. 幂等：同一任务已有 pending/approved 案例则拦截
        Long existing = neo4jClient.query(
                        "MATCH (c:CaseRecord) WHERE c.source_task_id = $taskId " +
                                "AND c.status IN ['pending','approved'] RETURN count(c) AS cnt")
                .bind(taskId).to("taskId")
                .fetchAs(Long.class)
                .mappedBy((t, r) -> r.get("cnt").asLong(0))
                .one().orElse(0L);
        if (existing > 0) {
            throw new TaskStateException("该任务已沉淀过案例");
        }
        // 4. 拼装任务上下文
        List<TaskStepRecord> steps = stepMapper.selectList(
                new LambdaQueryWrapper<TaskStepRecord>()
                        .eq(TaskStepRecord::getTaskId, taskId)
                        .orderByAsc(TaskStepRecord::getSortOrder));
        StringBuilder ctx = new StringBuilder();
        if (StringUtils.hasText(task.getDeviceName())) {
            ctx.append("设备：").append(task.getDeviceName()).append("\n");
        }
        if (StringUtils.hasText(task.getFaultDescription())) {
            ctx.append("故障描述：").append(task.getFaultDescription()).append("\n");
        }
        ctx.append("检修步骤：\n");
        for (TaskStepRecord s : steps) {
            ctx.append("第").append(s.getSortOrder()).append("步 ")
                    .append(s.getTitle() == null ? "" : s.getTitle());
            if (StringUtils.hasText(s.getContent())) ctx.append("：").append(s.getContent());
            if (StringUtils.hasText(s.getNote())) ctx.append("（工人备注：").append(s.getNote()).append("）");
            ctx.append("\n");
        }
        // 5. 调 Python 起草（云端 LLM 无法访问 localhost MinIO，图片先转 Base64）
        Map<String, Object> body = new HashMap<>();
        body.put("source_type", "task");
        body.put("task_context", ctx.toString());
        List<String> base64Images = imagesForLlm(task.getReportImages());
        if (base64Images != null && !base64Images.isEmpty()) {
            body.put("images", base64Images);
        }
        CaseDraftVO vo = new CaseDraftVO();
        try {
            String resp = webClient.post()
                    .uri("/ai/case/draft")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            JsonNode node = objectMapper.readTree(resp);
            vo.setTitle(jsonText(node, "title"));
            vo.setSummary(jsonText(node, "summary"));
            vo.setDiagnosis(jsonText(node, "diagnosis"));
            vo.setResolution(jsonText(node, "resolution"));
            vo.setResult(jsonText(node, "result"));
            vo.setExperienceSummary(jsonText(node, "experience_summary"));
            vo.setTags(jsonText(node, "tags"));
            if (node.hasNonNull("downtime")) vo.setDowntime(node.get("downtime").asInt());
            if (node.hasNonNull("cost")) vo.setCost(node.get("cost").asDouble());
        } catch (Exception e) {
            log.warn("[案例] AI 起草失败 taskId={}: {}", taskId, e.getMessage());
            throw new TaskStateException("AI 起草失败：" + e.getMessage());
        }
        // 6. 带入任务锚定线索（imageUrls 用原始 URL，Base64 仅供 AI 调用）
        vo.setSourceTaskId(taskId);
        vo.setDeviceId(task.getDeviceId());
        vo.setDeviceName(task.getDeviceName());
        vo.setFaultName(task.getFaultDescription());
        vo.setImageUrls(task.getReportImages());
        return vo;
    }

    /** 图片 URL 转 Base64（云端多模态需要），失败降级原始 URL，不阻断起草。 */
    private List<String> imagesForLlm(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return urls;
        }
        try {
            return multimodalEmbeddingUtils.downloadImagesToBase64(urls);
        } catch (Exception e) {
            log.warn("[案例] 图片转Base64失败，降级为原始URL: {}", e.getMessage());
            return urls;
        }
    }

    private static String jsonText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    protected CaseRecord toEntity(CaseRecordDTO caseRecordDTO) {
        CaseRecord caseRecord = new CaseRecord();
        BeanUtils.copyProperties(caseRecordDTO, caseRecord);
        return caseRecord;
    }
}
