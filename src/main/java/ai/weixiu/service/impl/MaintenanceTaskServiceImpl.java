package ai.weixiu.service.impl;

import ai.weixiu.config.RabbitMQConfig;
import ai.weixiu.entity.MaintenanceTask;
import ai.weixiu.entity.TaskStepRecord;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.exceprion.TaskStateException;
import ai.weixiu.mapper.MaintenanceTaskMapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
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
    private final RabbitTemplate rabbitTemplate;

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
        task.setStatus("CREATED");
        task.setStepCount(0);
        task.setReporterId(reporterId);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);

        // 立刻发MQ，状态改为 GENERATING
        sendGenerateMessage(task);
        task.setStatus("GENERATING");
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

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

        // 合规校验
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

    @Override
    @Transactional
    public void onGenerateSuccess(Long taskId, List<TaskStepRecordVO> steps) {
        MaintenanceTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("[任务] MQ回调：任务不存在 taskId={}", taskId);
            return;
        }
        if (!"GENERATING".equals(task.getStatus())) {
            log.warn("[任务] MQ回调：任务状态不是GENERATING taskId={} status={}", taskId, task.getStatus());
            return;
        }

        // 批量插入步骤
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
            record.setStatus("PENDING");
            record.setCreatedAt(LocalDateTime.now());
            stepMapper.insert(record);
        }

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

    // ==================== 私有方法 ====================

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
