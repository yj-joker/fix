package ai.weixiu.service.impl;

import ai.weixiu.config.RabbitMQConfig;
import ai.weixiu.entity.*;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.exceprion.TaskStateException;
import ai.weixiu.mapper.MaintenanceTaskMapper;
import ai.weixiu.mapper.ProcedureStepMapper;
import ai.weixiu.mapper.StandardProcedureMapper;
import ai.weixiu.mapper.TaskStepRecordMapper;
import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.dto.MaintenanceTaskDTO;
import ai.weixiu.pojo.dto.StepExecuteDTO;
import ai.weixiu.pojo.query.MaintenanceTaskQuery;
import ai.weixiu.pojo.vo.MaintenanceTaskVO;
import ai.weixiu.pojo.vo.TaskStepRecordVO;
import ai.weixiu.service.MaintenanceTaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MaintenanceTaskServiceImpl implements MaintenanceTaskService {

    private final MaintenanceTaskMapper taskMapper;
    private final TaskStepRecordMapper stepMapper;
    private final StandardProcedureMapper procedureMapper;
    private final ProcedureStepMapper procedureStepMapper;
    private final RabbitTemplate rabbitTemplate;
    private final Neo4jClient neo4jClient;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ==================== 创建任务 ====================

    @Override
    @Transactional
    public MaintenanceTaskVO createTask(MaintenanceTaskDTO dto, Long reporterId) {
        if (dto.getFaultDescription() == null || dto.getFaultDescription().isBlank()) {
            throw new IllegalArgumentException("故障描述不能为空");
        }

        MaintenanceTask task = new MaintenanceTask();
        BeanUtils.copyProperties(dto, task);
        task.setTaskNumber(generateTaskNumber());
        task.setStepCount(0);
        task.setReporterId(reporterId);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        // 尝试匹配标准规程：按设备名称模糊匹配设备类型 + 检修等级精确匹配
        StandardProcedure matched = matchProcedure(dto.getDeviceName(), dto.getMaintenanceLevel());
        boolean wantAdapt = Boolean.TRUE.equals(dto.getAiAdapt());

        if (matched != null && !wantAdapt) {
            // ======== 路径1：直接拷贝规程模板（秒级）========
            task.setProcedureId(matched.getId());
            task.setGenerateMode("PROCEDURE_COPY");
            task.setStatus("GENERATED");
            taskMapper.insert(task);

            int stepCount = copyStepsFromProcedure(task.getId(), matched.getId());
            task.setStepCount(stepCount);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            log.info("[任务] 直接拷贝规程 taskId={} procedureId={} procedureName={} 步骤数={}",
                    task.getId(), matched.getId(), matched.getName(), stepCount);

        } else if (matched != null) {
            // ======== 路径2：AI基于规程微调（10-20s）========
            task.setProcedureId(matched.getId());
            task.setGenerateMode("AI_ADAPT");
            task.setStatus("CREATED");
            taskMapper.insert(task);

            sendAdaptMessage(task, matched.getId());
            task.setStatus("GENERATING");
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            log.info("[任务] AI微调规程 taskId={} procedureId={} procedureName={}",
                    task.getId(), matched.getId(), matched.getName());

        } else {
            // ======== 路径3：AI从零生成（20-30s）========
            task.setGenerateMode("AI_GENERATE");
            task.setStatus("CREATED");
            taskMapper.insert(task);

            sendGenerateMessage(task);
            task.setStatus("GENERATING");
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            log.info("[任务] 未匹配到规程，AI从零生成 taskId={}", task.getId());
        }

        return toVO(task, null);
    }

    // ==================== 重试生成 ====================

    @Override
    @Transactional
    public void retryGenerate(Long taskId) {
        MaintenanceTask task = getTaskOrThrow(taskId);
        if (!"GENERATE_FAILED".equals(task.getStatus())) {
            throw new TaskStateException("只有生成失败的任务才能重试，当前状态: " + task.getStatus());
        }
        sendGenerateMessage(task);
        task.setStatus("GENERATING");
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("[任务] 重试生成 taskId={}", taskId);
    }

    // ==================== 开始执行 ====================

    @Override
    @Transactional
    public void startExecute(Long taskId) {
        MaintenanceTask task = getTaskOrThrow(taskId);
        if (!"GENERATED".equals(task.getStatus())) {
            throw new TaskStateException("只有已生成步骤的任务才能开始执行，当前状态: " + task.getStatus());
        }
        task.setStatus("EXECUTING");
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("[任务] 开始执行 taskId={}", taskId);
    }

    // ==================== 执行步骤（提交证据 → 发MQ给AI验证） ====================

    @Override
    @Transactional
    public TaskStepRecordVO executeStep(Long taskId, Long stepId, StepExecuteDTO dto) {
        MaintenanceTask task = getTaskOrThrow(taskId);
        if (!"EXECUTING".equals(task.getStatus())) {
            throw new TaskStateException("任务未在执行中，当前状态: " + task.getStatus());
        }

        TaskStepRecord step = stepMapper.selectById(stepId);
        if (step == null || !step.getTaskId().equals(taskId)) {
            throw new NotFoundException("步骤不存在");
        }
        if ("COMPLETED".equals(step.getStatus()) || "SUBMITTED".equals(step.getStatus())) {
            throw new TaskStateException("该步骤已提交或已完成，当前状态: " + step.getStatus());
        }

        // 合规校验：拍照/备注
        if (Boolean.TRUE.equals(step.getRequirePhoto())) {
            if (dto.getImages() == null || dto.getImages().isEmpty()) {
                throw new IllegalArgumentException("该步骤要求上传照片");
            }
        }
        if (Boolean.TRUE.equals(step.getRequireNote())) {
            if (dto.getNote() == null || dto.getNote().isBlank()) {
                throw new IllegalArgumentException("该步骤要求填写执行备注");
            }
        }

        // 合规校验：安全检查点 — 必须确认所有检查项后才能提交
        if (Boolean.TRUE.equals(step.getIsCheckpoint())) {
            if (!Boolean.TRUE.equals(dto.getCheckpointConfirmed())) {
                throw new IllegalArgumentException(
                        "该步骤为合规检查点，必须确认所有安全检查项后才能提交");
            }
            step.setCheckpointConfirmed(true);
        }

        // 保存证据，状态改为 SUBMITTED，等待AI验证
        step.setImages(dto.getImages());
        step.setNote(dto.getNote());
        step.setStatus("SUBMITTED");
        stepMapper.updateById(step);

        // 发MQ给Python做AI多模态验证
        sendStepVerifyMessage(task, step);

        log.info("[任务] 步骤提交等待AI验证 taskId={} stepId={} title={}", taskId, stepId, step.getTitle());
        return toStepVO(step);
    }

    // ==================== AI验证结果回调（由StepVerifyResultListener调用）====================

    @Override
    @Transactional
    public void onStepVerifyResult(Long stepId, Boolean aiPass, Double confidence, String reason) {
        TaskStepRecord step = stepMapper.selectById(stepId);
        if (step == null) {
            log.warn("[任务] AI验证回调：步骤不存在 stepId={}", stepId);
            return;
        }
        if (!"SUBMITTED".equals(step.getStatus())) {
            log.warn("[任务] AI验证回调：步骤状态不是SUBMITTED stepId={} status={}", stepId, step.getStatus());
            return;
        }

        step.setAiPass(aiPass);
        step.setAiConfidence(confidence != null ? java.math.BigDecimal.valueOf(confidence) : null);
        step.setAiReason(reason);

        if (confidence != null && confidence >= 0.85) {
            // 高置信度：自动通过，无需人工介入
            step.setStatus("COMPLETED");
            step.setCompletedAt(LocalDateTime.now());
            log.info("[任务] AI验证自动通过 stepId={} confidence={}", stepId, confidence);
        } else if (confidence != null && confidence >= 0.5) {
            // 中等置信度：AI认为基本合格，工人可查看反馈自行判断
            step.setStatus("AI_PASSED");
            log.info("[任务] AI验证通过（置信度中等），工人可查看反馈 stepId={} confidence={}", stepId, confidence);
        } else {
            // 低置信度：AI认为不合格，工人可选择重新提交或强制完成
            step.setStatus("AI_REJECTED");
            log.info("[任务] AI验证未通过，工人可重新提交或强制完成 stepId={} confidence={} reason={}", stepId, confidence, reason);
        }

        stepMapper.updateById(step);

        // 自动通过时检查任务是否全部完成
        if ("COMPLETED".equals(step.getStatus())) {
            MaintenanceTask task = taskMapper.selectById(step.getTaskId());
            if (task != null) {
                checkAllStepsCompleted(task);
            }
        }
    }

    // ==================== 工人强制完成步骤（AI_REJECTED 后） ====================

    @Override
    @Transactional
    public TaskStepRecordVO forceCompleteStep(Long taskId, Long stepId, String reason) {
        MaintenanceTask task = getTaskOrThrow(taskId);
        if (!"EXECUTING".equals(task.getStatus())) {
            throw new TaskStateException("任务未在执行中，当前状态: " + task.getStatus());
        }

        TaskStepRecord step = stepMapper.selectById(stepId);
        if (step == null || !step.getTaskId().equals(taskId)) {
            throw new NotFoundException("步骤不存在");
        }
        if (!"AI_REJECTED".equals(step.getStatus())) {
            throw new TaskStateException("只有AI验证未通过的步骤才能强制完成，当前状态: " + step.getStatus());
        }

        step.setStatus("COMPLETED");
        step.setNote(step.getNote() != null
                ? step.getNote() + " [工人强制完成: " + reason + "]"
                : "[工人强制完成: " + reason + "]");
        step.setCompletedAt(LocalDateTime.now());
        stepMapper.updateById(step);

        log.info("[任务] 工人强制完成步骤 taskId={} stepId={} aiConfidence={} reason={}",
                taskId, stepId, step.getAiConfidence(), reason);

        checkAllStepsCompleted(task);
        return toStepVO(step);
    }

    // ==================== 查询 ====================

    @Override
    public MaintenanceTaskVO getTaskDetail(Long taskId) {
        MaintenanceTask task = getTaskOrThrow(taskId);
        List<TaskStepRecord> steps = stepMapper.selectList(
                new LambdaQueryWrapper<TaskStepRecord>()
                        .eq(TaskStepRecord::getTaskId, taskId)
                        .orderByAsc(TaskStepRecord::getSortOrder)
        );
        return toVO(task, steps);
    }

    @Override
    public PageResult<MaintenanceTaskVO> listTasks(MaintenanceTaskQuery query) {
        int pageNum = query.getPage() != null ? query.getPage() : 1;
        int pageSize = query.getSize() != null ? query.getSize() : 10;
        Page<MaintenanceTask> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<MaintenanceTask> wrapper = new LambdaQueryWrapper<>();

        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(MaintenanceTask::getStatus, query.getStatus());
        }
        if (query.getDeviceName() != null && !query.getDeviceName().isBlank()) {
            wrapper.like(MaintenanceTask::getDeviceName, query.getDeviceName());
        }
        wrapper.orderByDesc(MaintenanceTask::getCreatedAt);

        Page<MaintenanceTask> result = taskMapper.selectPage(page, wrapper);
        List<MaintenanceTaskVO> vos = result.getRecords().stream()
                .map(t -> toVO(t, null))
                .collect(Collectors.toList());

        return new PageResult<>(vos, result.getTotal(), pageNum, pageSize);
    }

    @Override
    public List<TaskStepRecordVO> listSteps(Long taskId) {
        getTaskOrThrow(taskId);
        List<TaskStepRecord> steps = stepMapper.selectList(
                new LambdaQueryWrapper<TaskStepRecord>()
                        .eq(TaskStepRecord::getTaskId, taskId)
                        .orderByAsc(TaskStepRecord::getSortOrder)
        );
        return steps.stream().map(this::toStepVO).collect(Collectors.toList());
    }

    // ==================== MQ 回调 ====================

    @Override
    @Transactional
    public void onGenerateSuccess(Long taskId, List<TaskStepRecordVO> steps, Object graphExtraction) {
        MaintenanceTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("[任务] MQ回调：任务不存在 taskId={}", taskId);
            return;
        }
        if (!"GENERATING".equals(task.getStatus())) {
            log.warn("[任务] MQ回调：任务状态不是GENERATING taskId={} status={}", taskId, task.getStatus());
            return;
        }

        // 保存AI提取的图谱线索（供后续沉淀时管理员确认）
        if (graphExtraction != null) {
            task.setGraphExtraction(graphExtraction);
            log.info("[任务] 已保存AI提取的图谱线索 taskId={}", taskId);
        }

        // 批量插入步骤，记录来源和置信度
        List<TaskStepRecord> records = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            TaskStepRecordVO vo = steps.get(i);
            TaskStepRecord record = new TaskStepRecord();
            record.setTaskId(taskId);
            record.setSortOrder(i + 1);
            record.setTitle(vo.getTitle());
            record.setContent(vo.getContent());
            record.setSafetyNote(vo.getSafetyNote());
            record.setRequirePhoto(vo.getRequirePhoto() != null ? vo.getRequirePhoto() : false);
            record.setRequireNote(vo.getRequireNote() != null ? vo.getRequireNote() : false);
            record.setEstimatedMinutes(vo.getEstimatedMinutes());
            record.setSources(vo.getSources());
            record.setGenerateConfidence(vo.getGenerateConfidence());
            record.setStatus("PENDING");
            record.setCreatedAt(LocalDateTime.now());
            records.add(record);
        }
        Db.saveBatch(records);

        task.setStatus("GENERATED");
        task.setStepCount(steps.size());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("[任务] 步骤生成成功 taskId={} 步骤数={}", taskId, steps.size());
    }

    @Override
    @Transactional
    public void onGenerateFailed(Long taskId, String errorMsg) {
        MaintenanceTask task = taskMapper.selectById(taskId);
        if (task == null) return;
        if (!"GENERATING".equals(task.getStatus())) return;

        task.setStatus("GENERATE_FAILED");
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.error("[任务] 步骤生成失败 taskId={} error={}", taskId, errorMsg);
    }

    // ==================== 知识沉淀 ====================

    @Override
    @Transactional
    public Long promoteToStandardProcedure(Long taskId, Long operatorId) {
        MaintenanceTask task = getTaskOrThrow(taskId);
        if (!"CLOSED".equals(task.getStatus())) {
            throw new TaskStateException("只有已关闭的任务才能沉淀为标准规程，当前状态: " + task.getStatus());
        }

        // 重复沉淀保护
        if (Boolean.TRUE.equals(task.getPromotedProcedure())) {
            throw new TaskStateException("该任务已沉淀为标准规程，不可重复操作");
        }

        // 查任务步骤
        List<TaskStepRecord> taskSteps = stepMapper.selectList(
                new LambdaQueryWrapper<TaskStepRecord>()
                        .eq(TaskStepRecord::getTaskId, taskId)
                        .orderByAsc(TaskStepRecord::getSortOrder)
        );
        if (taskSteps.isEmpty()) {
            throw new TaskStateException("任务没有步骤，无法沉淀");
        }

        // 校验所有步骤是否都已完成（COMPLETED / AI_PASSED / SKIPPED 视为可沉淀状态）
        List<String> acceptableStatus = List.of("COMPLETED", "AI_PASSED", "SKIPPED");
        List<TaskStepRecord> unfinished = taskSteps.stream()
                .filter(s -> !acceptableStatus.contains(s.getStatus()))
                .collect(Collectors.toList());
        if (!unfinished.isEmpty()) {
            String detail = unfinished.stream()
                    .map(s -> s.getTitle() + "(" + s.getStatus() + ")")
                    .collect(Collectors.joining(", "));
            throw new TaskStateException("存在未完成的步骤，无法沉淀: " + detail);
        }

        // 创建标准规程（DRAFT 状态，管理员还需编辑后发布）
        StandardProcedure procedure = new StandardProcedure();
        procedure.setName(task.getDeviceName() + " 检修流程（来自任务 " + task.getTaskNumber() + "）");
        procedure.setDeviceType(task.getDeviceName());
        procedure.setMaintenanceLevel(task.getMaintenanceLevel());
        procedure.setDescription("从检修任务 " + task.getTaskNumber() + " 沉淀而来：" + task.getFaultDescription());
        procedure.setVersion(1);
        procedure.setStatus("DRAFT");
        procedure.setSourceType("TASK_PROMOTE");
        procedure.setSourceTaskId(taskId);
        procedure.setTotalSteps(taskSteps.size());
        procedure.setCreatedBy(operatorId);
        procedure.setCreatedAt(LocalDateTime.now());
        procedure.setUpdatedAt(LocalDateTime.now());
        procedureMapper.insert(procedure);

        // 批量拷贝步骤为规程模板
        List<ProcedureStep> procedureSteps = taskSteps.stream().map(step -> {
            ProcedureStep ps = new ProcedureStep();
            ps.setProcedureId(procedure.getId());
            ps.setStepOrder(step.getSortOrder());
            ps.setTitle(step.getTitle());
            ps.setContent(step.getContent());
            ps.setSafetyNote(step.getSafetyNote());
            ps.setIsCheckpoint(Boolean.TRUE.equals(step.getIsCheckpoint()));
            ps.setCheckpointItems(step.getCheckpointItems());
            ps.setEstimatedMinutes(step.getEstimatedMinutes());
            ps.setCreatedAt(LocalDateTime.now());
            return ps;
        }).collect(Collectors.toList());
        Db.saveBatch(procedureSteps);

        // 标记已沉淀
        task.setPromotedProcedure(true);
        taskMapper.updateById(task);

        log.info("[知识沉淀] 任务沉淀为标准规程 taskId={} procedureId={} 步骤数={}",
                taskId, procedure.getId(), taskSteps.size());
        return procedure.getId();
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public void promoteToGraph(Long taskId, Map<String, Object> graphData) {
        MaintenanceTask task = getTaskOrThrow(taskId);
        if (!"CLOSED".equals(task.getStatus())) {
            throw new TaskStateException("只有已关闭的任务才能沉淀到图谱，当前状态: " + task.getStatus());
        }

        // 重复沉淀保护
        if (Boolean.TRUE.equals(task.getPromotedGraph())) {
            throw new TaskStateException("该任务已沉淀到知识图谱，不可重复操作");
        }

        // 如果前端没传 graphData 内容，则用 AI 提取的 graphExtraction 作为默认数据
        if (graphData == null || graphData.isEmpty()) {
            if (task.getGraphExtraction() instanceof Map) {
                graphData = (Map<String, Object>) task.getGraphExtraction();
            } else {
                throw new IllegalArgumentException("没有可用的图谱数据，请提供沉淀内容或等待AI提取完成");
            }
        }

        String deviceName = (String) graphData.get("deviceName");
        if (deviceName == null || deviceName.isBlank()) {
            // 兜底：用任务的设备名称
            deviceName = task.getDeviceName();
        }
        if (deviceName == null || deviceName.isBlank()) {
            throw new IllegalArgumentException("设备名称不能为空");
        }

        // 1. 查找或创建 Device 节点
        String deviceNodeId = findOrCreateDevice(deviceName);

        // 2. 处理 components + faults + solutions
        List<Map<String, Object>> components = (List<Map<String, Object>>) graphData.getOrDefault("components", List.of());
        List<Map<String, Object>> faults = (List<Map<String, Object>>) graphData.getOrDefault("faults", List.of());
        List<Map<String, Object>> solutions = (List<Map<String, Object>>) graphData.getOrDefault("solutions", List.of());

        // component name → neo4j id 映射
        Map<String, String> componentIdMap = new HashMap<>();
        for (Map<String, Object> comp : components) {
            String compName = (String) comp.get("name");
            if (compName == null || compName.isBlank()) continue;
            String compId = findOrCreateComponent(compName, deviceNodeId);
            componentIdMap.put(compName, compId);
        }

        // fault name → neo4j id 映射
        Map<String, String> faultIdMap = new HashMap<>();
        for (Map<String, Object> fault : faults) {
            String faultName = (String) fault.get("name");
            if (faultName == null || faultName.isBlank()) continue;
            String severity = (String) fault.getOrDefault("severity", "一般");
            String relatedComp = (String) fault.get("relatedComponent");

            String faultId = createFaultNode(faultName, severity, task.getFaultDescription());
            faultIdMap.put(faultName, faultId);

            // 关联 Component → Fault (CAUSES)
            String compId = componentIdMap.get(relatedComp);
            if (compId != null) {
                createRelationship(compId, faultId, "CAUSES");
            }
            // 关联 Device → Fault (HAS_FAULT)
            createRelationship(deviceNodeId, faultId, "HAS_FAULT");
        }

        // 3. 创建 Solution 节点
        Long procedureId = (graphData.get("procedureId") instanceof Number)
                ? ((Number) graphData.get("procedureId")).longValue() : null;

        for (Map<String, Object> sol : solutions) {
            String solTitle = (String) sol.get("title");
            if (solTitle == null || solTitle.isBlank()) continue;
            String summary = (String) sol.getOrDefault("summary", "");
            String relatedFault = (String) sol.get("relatedFault");

            String solId = createSolutionNode(solTitle, summary, procedureId, taskId);

            // 关联 Fault → Solution (HAS_SOLUTION)
            String faultId = faultIdMap.get(relatedFault);
            if (faultId != null) {
                createRelationship(faultId, solId, "HAS_SOLUTION");
            }
        }

        // 标记已沉淀
        task.setPromotedGraph(true);
        taskMapper.updateById(task);

        log.info("[知识沉淀] 任务沉淀到图谱完成 taskId={} 设备={} 部件数={} 故障数={} 方案数={}",
                taskId, deviceName, components.size(), faults.size(), solutions.size());
    }

    // ==================== 图谱操作私有方法 ====================

    private String findOrCreateDevice(String deviceName) {
        // MERGE: 按 name 查找，不存在则创建；ON CREATE 时赋 id
        return neo4jClient.query(
                "MERGE (d:Device {name: $name}) " +
                "ON CREATE SET d.id = randomUUID(), d.created_at = datetime() " +
                "RETURN d.id AS id"
        ).bind(deviceName).to("name")
        .fetchAs(String.class)
        .one()
        .orElseThrow(() -> new RuntimeException("创建设备节点失败: " + deviceName));
    }

    private String findOrCreateComponent(String compName, String deviceNodeId) {
        // 先 MATCH 设备，再 MERGE 该设备下的部件（通过关系绑定唯一性）
        return neo4jClient.query(
                "MATCH (d:Device {id: $deviceId}) " +
                "MERGE (d)-[:OWNS]->(c:Component {name: $name}) " +
                "ON CREATE SET c.id = randomUUID() " +
                "RETURN c.id AS id"
        ).bind(deviceNodeId).to("deviceId")
        .bind(compName).to("name")
        .fetchAs(String.class)
        .one()
        .orElseThrow(() -> new RuntimeException("创建部件节点失败: " + compName));
    }
    private String createFaultNode(String name, String severity, String description) {
        return neo4jClient.query(
                "CREATE (f:Fault {id: randomUUID(), name: $name, severity: $severity, description: $description, created_at: datetime()}) " +
                "RETURN f.id AS id"
        ).bind(name).to("name")
        .bind(severity).to("severity")
        .bind(description).to("description")
        .fetchAs(String.class)
        .one()
        .orElseThrow(() -> new RuntimeException("创建故障节点失败: " + name));
    }

    private String createSolutionNode(String title, String summary, Long procedureId, Long sourceTaskId) {
        return neo4jClient.query(
                "CREATE (s:Solution {id: randomUUID(), title: $title, description: $summary, verified: true, " +
                "procedure_id: $procedureId, source_task_id: $sourceTaskId, created_at: datetime()}) " +
                "RETURN s.id AS id"
        ).bind(title).to("title")
        .bind(summary).to("summary")
        .bind(procedureId).to("procedureId")
        .bind(sourceTaskId).to("sourceTaskId")
        .fetchAs(String.class)
        .one()
        .orElseThrow(() -> new RuntimeException("创建解决方案节点失败: " + title));
    }

    private void createRelationship(String fromId, String toId, String relType) {
        // 根据关系类型用对应 Cypher（Neo4j 不支持动态关系类型参数化）
        String cypher = switch (relType) {
            case "OWNS" -> "MATCH (a {id: $fromId}), (b {id: $toId}) MERGE (a)-[:OWNS]->(b)";
            case "CAUSES" -> "MATCH (a {id: $fromId}), (b {id: $toId}) MERGE (a)-[:CAUSES]->(b)";
            case "HAS_FAULT" -> "MATCH (a {id: $fromId}), (b {id: $toId}) MERGE (a)-[:HAS_FAULT]->(b)";
            case "HAS_SOLUTION" -> "MATCH (a {id: $fromId}), (b {id: $toId}) MERGE (a)-[:HAS_SOLUTION]->(b)";
            default -> throw new IllegalArgumentException("未知的关系类型: " + relType);
        };
        neo4jClient.query(cypher)
                .bind(fromId).to("fromId")
                .bind(toId).to("toId")
                .run();
    }

    // ==================== 私有方法 ====================

    /**
     * 匹配标准规程：设备名称包含规程的设备类型 + 检修等级匹配
     * 优先匹配有检修等级的，其次匹配无等级限制的；取最新版本
     */
    private StandardProcedure matchProcedure(String deviceName, String maintenanceLevel) {
        if (deviceName == null || deviceName.isBlank()) {
            return null;
        }

        // 查所有已发布的规程
        List<StandardProcedure> published = procedureMapper.selectList(
                new LambdaQueryWrapper<StandardProcedure>()
                        .eq(StandardProcedure::getStatus, "PUBLISHED")
                        .orderByDesc(StandardProcedure::getVersion)
        );

        StandardProcedure best = null;
        for (StandardProcedure p : published) {
            // 设备类型匹配：设备名称包含规程的设备类型关键字
            if (p.getDeviceType() != null && !p.getDeviceType().isBlank()
                    && deviceName.contains(p.getDeviceType())) {
                // 检修等级匹配
                if (maintenanceLevel != null && maintenanceLevel.equals(p.getMaintenanceLevel())) {
                    return p; // 完全匹配（设备类型+等级），直接返回
                }
                if (best == null && (p.getMaintenanceLevel() == null || p.getMaintenanceLevel().isBlank())) {
                    best = p; // 设备类型匹配但规程无等级限制，作为备选
                }
            }
        }
        return best;
    }

    /**
     * 从标准规程拷贝步骤到任务，返回拷贝的步骤数
     */
    private int copyStepsFromProcedure(Long taskId, Long procedureId) {
        List<ProcedureStep> templateSteps = procedureStepMapper.selectList(
                new LambdaQueryWrapper<ProcedureStep>()
                        .eq(ProcedureStep::getProcedureId, procedureId)
                        .orderByAsc(ProcedureStep::getStepOrder)
        );

        List<TaskStepRecord> records = templateSteps.stream().map(ps -> {
            TaskStepRecord record = new TaskStepRecord();
            record.setTaskId(taskId);
            record.setSortOrder(ps.getStepOrder());
            record.setTitle(ps.getTitle());
            record.setContent(ps.getContent());
            record.setSafetyNote(ps.getSafetyNote());
            record.setRequirePhoto(true);
            record.setRequireNote(false);
            record.setEstimatedMinutes(ps.getEstimatedMinutes());
            record.setIsCheckpoint(Boolean.TRUE.equals(ps.getIsCheckpoint()));
            record.setCheckpointItems(ps.getCheckpointItems());
            record.setCheckpointConfirmed(false);
            record.setStatus("PENDING");
            record.setGenerateConfidence(java.math.BigDecimal.ONE);
            record.setCreatedAt(LocalDateTime.now());
            return record;
        }).collect(Collectors.toList());
        Db.saveBatch(records);

        return templateSteps.size();
    }

    private void sendGenerateMessage(MaintenanceTask task) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("taskId", task.getId());
        msg.put("taskNumber", task.getTaskNumber());
        msg.put("deviceId", task.getDeviceId());
        msg.put("deviceName", task.getDeviceName());
        msg.put("faultDescription", task.getFaultDescription());
        msg.put("urgencyLevel", task.getUrgencyLevel());
        msg.put("reportImages", task.getReportImages());
        msg.put("generateMode", "AI_GENERATE");
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.TASK_EXCHANGE,
                RabbitMQConfig.TASK_GENERATE_KEY,
                msg
        );
        log.info("[任务] 发送AI从零生成消息 taskId={} taskNumber={}", task.getId(), task.getTaskNumber());
    }

    /**
     * 发送AI微调消息：携带标准规程的模板步骤，让AI根据具体故障做个性化调整
     * Python端收到 procedureSteps 后走微调模式，而非从零生成
     */
    private void sendAdaptMessage(MaintenanceTask task, Long procedureId) {
        // 查询规程模板步骤
        List<ProcedureStep> templateSteps = procedureStepMapper.selectList(
                new LambdaQueryWrapper<ProcedureStep>()
                        .eq(ProcedureStep::getProcedureId, procedureId)
                        .orderByAsc(ProcedureStep::getStepOrder)
        );

        // 将模板步骤转为简洁的Map列表
        List<Map<String, Object>> stepList = templateSteps.stream().map(ps -> {
            Map<String, Object> stepMap = new HashMap<>();
            stepMap.put("stepOrder", ps.getStepOrder());
            stepMap.put("title", ps.getTitle());
            stepMap.put("content", ps.getContent());
            stepMap.put("safetyNote", ps.getSafetyNote());
            stepMap.put("isCheckpoint", Boolean.TRUE.equals(ps.getIsCheckpoint()));
            stepMap.put("checkpointItems", ps.getCheckpointItems());
            stepMap.put("estimatedMinutes", ps.getEstimatedMinutes());
            return stepMap;
        }).collect(Collectors.toList());

        Map<String, Object> msg = new HashMap<>();
        msg.put("taskId", task.getId());
        msg.put("taskNumber", task.getTaskNumber());
        msg.put("deviceId", task.getDeviceId());
        msg.put("deviceName", task.getDeviceName());
        msg.put("faultDescription", task.getFaultDescription());
        msg.put("urgencyLevel", task.getUrgencyLevel());
        msg.put("reportImages", task.getReportImages());
        msg.put("generateMode", "AI_ADAPT");
        msg.put("procedureSteps", stepList);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.TASK_EXCHANGE,
                RabbitMQConfig.TASK_GENERATE_KEY,
                msg
        );
        log.info("[任务] 发送AI微调消息 taskId={} procedureId={} 模板步骤数={}",
                task.getId(), procedureId, templateSteps.size());
    }

    private void sendStepVerifyMessage(MaintenanceTask task, TaskStepRecord step) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("taskId", task.getId());
        msg.put("stepId", step.getId());
        msg.put("stepTitle", step.getTitle());
        msg.put("stepContent", step.getContent());
        msg.put("safetyNote", step.getSafetyNote());
        msg.put("images", step.getImages());
        msg.put("note", step.getNote());
        msg.put("deviceName", task.getDeviceName());
        msg.put("faultDescription", task.getFaultDescription());
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.TASK_EXCHANGE,
                RabbitMQConfig.TASK_STEP_VERIFY_KEY,
                msg
        );
        log.info("[任务] 发送步骤AI验证消息 taskId={} stepId={}", task.getId(), step.getId());
    }

    private void checkAllStepsCompleted(MaintenanceTask task) {
        Long count = stepMapper.selectCount(
                new LambdaQueryWrapper<TaskStepRecord>()
                        .eq(TaskStepRecord::getTaskId, task.getId())
                        .ne(TaskStepRecord::getStatus, "COMPLETED")
        );
        if (count == 0) {
            task.setStatus("CLOSED");
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            log.info("[任务] 所有步骤完成，任务关闭 taskId={}", task.getId());
        }
    }

    private String generateTaskNumber() {
        String date = LocalDate.now().format(DATE_FMT);
        String random = String.format("%03d", new Random().nextInt(1000));
        return "MT-" + date + "-" + random;
    }

    private MaintenanceTask getTaskOrThrow(Long taskId) {
        MaintenanceTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new NotFoundException("任务不存在: " + taskId);
        }
        return task;
    }

    private MaintenanceTaskVO toVO(MaintenanceTask task, List<TaskStepRecord> steps) {
        MaintenanceTaskVO vo = new MaintenanceTaskVO();
        BeanUtils.copyProperties(task, vo);
        // 填充规程名称
        if (task.getProcedureId() != null) {
            StandardProcedure procedure = procedureMapper.selectById(task.getProcedureId());
            if (procedure != null) {
                vo.setProcedureName(procedure.getName());
            }
        }
        if (steps != null) {
            vo.setSteps(steps.stream().map(this::toStepVO).collect(Collectors.toList()));
        }
        return vo;
    }

    private TaskStepRecordVO toStepVO(TaskStepRecord record) {
        TaskStepRecordVO vo = new TaskStepRecordVO();
        BeanUtils.copyProperties(record, vo);
        return vo;
    }
}
