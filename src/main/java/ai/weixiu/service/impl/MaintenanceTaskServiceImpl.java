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

        if (matched != null) {
            // 匹配到标准规程 → 从规程模板拷贝步骤，跳过AI生成
            task.setProcedureId(matched.getId());
            task.setStatus("GENERATED");
            taskMapper.insert(task);

            int stepCount = copyStepsFromProcedure(task.getId(), matched.getId());
            task.setStepCount(stepCount);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            log.info("[任务] 匹配到标准规程 taskId={} procedureId={} procedureName={} 步骤数={}",
                    task.getId(), matched.getId(), matched.getName(), stepCount);
        } else {
            // 未匹配到规程 → 走原有AI生成流程
            task.setStatus("CREATED");
            taskMapper.insert(task);

            sendGenerateMessage(task);
            task.setStatus("GENERATING");
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            log.info("[任务] 未匹配到标准规程，走AI生成 taskId={}", task.getId());
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

    // ==================== 管理员审核步骤内容（专家审核） ====================

    @Override
    @Transactional
    public void expertReview(Long taskId, List<Map<String, Object>> stepReviews, Long reviewerId) {
        MaintenanceTask task = getTaskOrThrow(taskId);
        if (!"PENDING_EXPERT_REVIEW".equals(task.getStatus())) {
            throw new TaskStateException("只有待专家审核的任务才能审核，当前状态: " + task.getStatus());
        }

        for (Map<String, Object> review : stepReviews) {
            Long stepId = ((Number) review.get("stepId")).longValue();
            TaskStepRecord step = stepMapper.selectById(stepId);
            if (step == null || !step.getTaskId().equals(taskId)) {
                throw new NotFoundException("步骤不存在: " + stepId);
            }

            // 管理员可直接编辑步骤内容
            String editedTitle = (String) review.get("editedTitle");
            String editedContent = (String) review.get("editedContent");
            String editedSafetyNote = (String) review.get("editedSafetyNote");
            if (editedTitle != null && !editedTitle.isBlank()) {
                step.setTitle(editedTitle);
            }
            if (editedContent != null && !editedContent.isBlank()) {
                step.setContent(editedContent);
            }
            if (editedSafetyNote != null && !editedSafetyNote.isBlank()) {
                step.setSafetyNote(editedSafetyNote);
            }

            step.setReviewerId(reviewerId);
            step.setReviewStatus("EXPERT_APPROVED");
            step.setReviewedAt(LocalDateTime.now());
            stepMapper.updateById(step);
            log.info("[任务] 管理员审核步骤 taskId={} stepId={}", taskId, stepId);
        }

        // 审核完成 → 任务进入 GENERATED 状态，可以开始执行
        task.setStatus("GENERATED");
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("[任务] 管理员审核完成，任务可执行 taskId={}", taskId);
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

    // ==================== 管理员审核步骤 ====================

    @Override
    @Transactional
    public TaskStepRecordVO reviewStep(Long taskId, Long stepId, boolean approved, String reviewNote, Long reviewerId) {
        MaintenanceTask task = getTaskOrThrow(taskId);
        if (!"EXECUTING".equals(task.getStatus())) {
            throw new TaskStateException("任务未在执行中，当前状态: " + task.getStatus());
        }

        TaskStepRecord step = stepMapper.selectById(stepId);
        if (step == null || !step.getTaskId().equals(taskId)) {
            throw new NotFoundException("步骤不存在");
        }
        if (!"PENDING_REVIEW".equals(step.getStatus())) {
            throw new TaskStateException("该步骤不在待审核状态，当前状态: " + step.getStatus());
        }

        step.setReviewerId(reviewerId);
        step.setReviewNote(reviewNote);
        step.setReviewedAt(LocalDateTime.now());

        if (approved) {
            step.setReviewStatus("APPROVED");
            step.setStatus("COMPLETED");
            step.setCompletedAt(LocalDateTime.now());
            log.info("[任务] 管理员审核通过 taskId={} stepId={}", taskId, stepId);
        } else {
            step.setReviewStatus("REJECTED");
            step.setStatus("PENDING");
            step.setAiPass(null);
            step.setAiConfidence(null);
            step.setAiReason(null);
            step.setImages(null);
            step.setNote(null);
            log.info("[任务] 管理员驳回，工人需重新提交 taskId={} stepId={}", taskId, stepId);
        }

        stepMapper.updateById(step);

        if (approved) {
            checkAllStepsCompleted(task);
        }

        return toStepVO(step);
    }

    // ==================== AI验证结果回调（由StepVerifyResultListener调用）====================

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
            step.setStatus("COMPLETED");
            step.setCompletedAt(LocalDateTime.now());
            log.info("[任务] AI验证自动通过 stepId={} confidence={}", stepId, confidence);
        } else if (confidence != null && confidence >= 0.5) {
            step.setStatus("AI_PASSED");
            log.info("[任务] AI验证通过但建议补充 stepId={} confidence={}", stepId, confidence);
        } else {
            step.setStatus("PENDING_REVIEW");
            step.setReviewStatus("PENDING_REVIEW");
            log.info("[任务] AI验证置信度低，转人工审核 stepId={} confidence={}", stepId, confidence);
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

    /** 生成置信度阈值：低于此值的步骤需要管理员审核 */
    private static final double GENERATE_CONFIDENCE_THRESHOLD = 0.70;

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
        boolean hasLowConfidence = false;
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

            if (vo.getGenerateConfidence() == null
                    || vo.getGenerateConfidence().doubleValue() < GENERATE_CONFIDENCE_THRESHOLD) {
                hasLowConfidence = true;
            }
        }
        Db.saveBatch(records);

        // 有低置信度步骤 → 需要管理员先审核步骤内容再执行
        if (hasLowConfidence) {
            task.setStatus("PENDING_EXPERT_REVIEW");
            log.info("[任务] 存在低置信度步骤，等待管理员审核 taskId={}", taskId);
        } else {
            task.setStatus("GENERATED");
            log.info("[任务] 所有步骤置信度达标，可直接执行 taskId={}", taskId);
        }

        task.setStepCount(steps.size());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("[任务] 步骤生成成功 taskId={} 步骤数={} 状态={}", taskId, steps.size(), task.getStatus());
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

        // 查任务步骤
        List<TaskStepRecord> taskSteps = stepMapper.selectList(
                new LambdaQueryWrapper<TaskStepRecord>()
                        .eq(TaskStepRecord::getTaskId, taskId)
                        .orderByAsc(TaskStepRecord::getSortOrder)
        );
        if (taskSteps.isEmpty()) {
            throw new TaskStateException("任务没有步骤，无法沉淀");
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

        String deviceName = (String) graphData.get("deviceName");
        if (deviceName == null || deviceName.isBlank()) {
            throw new IllegalArgumentException("设备名称不能为空");
        }

        // 1. 查找或创建 Device 节点
        String deviceId = findOrCreateDevice(deviceName);

        // 2. 处理 components + faults + solutions
        List<Map<String, Object>> components = (List<Map<String, Object>>) graphData.getOrDefault("components", List.of());
        List<Map<String, Object>> faults = (List<Map<String, Object>>) graphData.getOrDefault("faults", List.of());
        List<Map<String, Object>> solutions = (List<Map<String, Object>>) graphData.getOrDefault("solutions", List.of());

        // component name → neo4j id 映射
        Map<String, String> componentIdMap = new HashMap<>();
        for (Map<String, Object> comp : components) {
            String compName = (String) comp.get("name");
            if (compName == null || compName.isBlank()) continue;
            String compId = findOrCreateComponent(compName, deviceId);
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
            createRelationship(deviceId, faultId, "HAS_FAULT");
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

        log.info("[知识沉淀] 任务沉淀到图谱完成 taskId={} 设备={} 部件数={} 故障数={} 方案数={}",
                taskId, deviceName, components.size(), faults.size(), solutions.size());
    }

    // ==================== 图谱操作私有方法 ====================

    private String findOrCreateDevice(String deviceName) {
        // 先按名称精确查找
        return neo4jClient.query(
                "OPTIONAL MATCH (d:Device) WHERE d.name = $name " +
                "WITH d " +
                "CALL { WITH d " +
                "  WITH d WHERE d IS NULL " +
                "  CREATE (n:Device {name: $name, created_at: datetime()}) RETURN n " +
                "  UNION " +
                "  WITH d WHERE d IS NOT NULL RETURN d AS n " +
                "} RETURN n.id AS id"
        ).bind(deviceName).to("name")
        .fetchAs(String.class)
        .one()
        .orElseThrow(() -> new RuntimeException("创建设备节点失败: " + deviceName));
    }

    private String findOrCreateComponent(String compName, String deviceId) {
        // 先查该设备下是否已有此部件
        return neo4jClient.query(
                "OPTIONAL MATCH (d:Device {id: $deviceId})-[:OWNS]->(c:Component) WHERE c.name = $name " +
                "WITH d, c " +
                "CALL { WITH d, c " +
                "  WITH d, c WHERE c IS NULL " +
                "  CREATE (n:Component {name: $name}) " +
                "  WITH d, n MERGE (d)-[:OWNS]->(n) RETURN n " +
                "  UNION " +
                "  WITH d, c WHERE c IS NOT NULL RETURN c AS n " +
                "} RETURN n.id AS id"
        ).bind(deviceId).to("deviceId")
        .bind(compName).to("name")
        .fetchAs(String.class)
        .one()
        .orElseThrow(() -> new RuntimeException("创建部件节点失败: " + compName));
    }

    private String createFaultNode(String name, String severity, String description) {
        return neo4jClient.query(
                "CREATE (f:Fault {name: $name, severity: $severity, description: $description, created_at: datetime()}) " +
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
                "CREATE (s:Solution {title: $title, description: $summary, verified: true, " +
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
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.TASK_EXCHANGE,
                RabbitMQConfig.TASK_GENERATE_KEY,
                msg
        );
        log.info("[任务] 发送生成消息 taskId={} taskNumber={}", task.getId(), task.getTaskNumber());
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
