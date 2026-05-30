package ai.weixiu.controller;

import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.dto.MaintenanceTaskDTO;
import ai.weixiu.pojo.dto.StepExecuteDTO;
import ai.weixiu.pojo.query.MaintenanceTaskQuery;
import ai.weixiu.pojo.vo.MaintenanceTaskVO;
import ai.weixiu.pojo.vo.TaskStepRecordVO;
import ai.weixiu.service.MaintenanceTaskService;
import ai.weixiu.utils.BaseContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/weixiu/task")
@RequiredArgsConstructor
public class MaintenanceTaskController {

    private final MaintenanceTaskService taskService;
    private final WebClient webClient;

    /** 创建检修任务（自动触发LLM生成步骤） */
    @PostMapping
    public Result<MaintenanceTaskVO> createTask(@RequestBody MaintenanceTaskDTO dto) {
        Long userId = BaseContext.getCurrentId();
        MaintenanceTaskVO vo = taskService.createTask(dto, userId);
        return Result.success(vo);
    }

    /** 重试生成步骤（GENERATE_FAILED → GENERATING） */
    @PostMapping("/{taskId}/retry")
    public Result<Void> retryGenerate(@PathVariable Long taskId) {
        taskService.retryGenerate(taskId);
        return Result.success(null);
    }

    /** 管理员审核步骤内容（PENDING_EXPERT_REVIEW → GENERATED） */
    @PostMapping("/{taskId}/expert-review")
    @SuppressWarnings("unchecked")
    public Result<Void> expertReview(
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body) {
        Long reviewerId = BaseContext.getCurrentId();
        List<Map<String, Object>> stepReviews = (List<Map<String, Object>>) body.get("stepReviews");
        taskService.expertReview(taskId, stepReviews, reviewerId);
        return Result.success(null);
    }

    /** 开始执行任务（GENERATED → EXECUTING） */
    @PostMapping("/{taskId}/start")
    public Result<Void> startExecute(@PathVariable Long taskId) {
        taskService.startExecute(taskId);
        return Result.success(null);
    }

    /** 执行某一步骤 */
    @PostMapping("/{taskId}/steps/{stepId}/execute")
    public Result<TaskStepRecordVO> executeStep(
            @PathVariable Long taskId,
            @PathVariable Long stepId,
            @RequestBody StepExecuteDTO dto) {
        TaskStepRecordVO vo = taskService.executeStep(taskId, stepId, dto);
        return Result.success(vo);
    }

    /** 查询任务详情（含步骤列表） */
    @GetMapping("/{taskId}")
    public Result<MaintenanceTaskVO> getTaskDetail(@PathVariable Long taskId) {
        MaintenanceTaskVO vo = taskService.getTaskDetail(taskId);
        return Result.success(vo);
    }

    /** 分页查询任务列表 */
    @GetMapping
    public Result<PageResult<MaintenanceTaskVO>> listTasks(MaintenanceTaskQuery query) {
        PageResult<MaintenanceTaskVO> result = taskService.listTasks(query);
        return Result.success(result);
    }

    /** 查询任务的步骤列表 */
    @GetMapping("/{taskId}/steps")
    public Result<List<TaskStepRecordVO>> listSteps(@PathVariable Long taskId) {
        List<TaskStepRecordVO> steps = taskService.listSteps(taskId);
        return Result.success(steps);
    }

    /** 管理员审核步骤（PENDING_REVIEW → COMPLETED / PENDING） */
    @PostMapping("/{taskId}/steps/{stepId}/review")
    public Result<TaskStepRecordVO> reviewStep(
            @PathVariable Long taskId,
            @PathVariable Long stepId,
            @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        String reviewNote = (String) body.get("reviewNote");
        Long reviewerId = BaseContext.getCurrentId();
        TaskStepRecordVO vo = taskService.reviewStep(taskId, stepId, approved, reviewNote, reviewerId);
        return Result.success(vo);
    }

    /** 沉淀为标准规程（CLOSED → 创建 StandardProcedure，返回规程ID） */
    @PostMapping("/{taskId}/promote")
    public Result<Long> promoteToStandardProcedure(@PathVariable Long taskId) {
        Long operatorId = BaseContext.getCurrentId();
        Long procedureId = taskService.promoteToStandardProcedure(taskId, operatorId);
        return Result.success(procedureId);
    }

    /** 沉淀到知识图谱（CLOSED → 创建图谱节点，管理员确认后提交） */
    @PostMapping("/{taskId}/promote-to-graph")
    public Result<Void> promoteToGraph(
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> graphData) {
        taskService.promoteToGraph(taskId, graphData);
        return Result.success(null);
    }

    /** 步骤对话辅助（转发到 Python FixAgent，实时SSE流） */
    @PostMapping(value = "/{taskId}/steps/{stepId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stepChat(
            @PathVariable Long taskId,
            @PathVariable Long stepId,
            @RequestBody Map<String, Object> body) {
        String sessionId = "task-" + taskId + "-step-" + stepId;
        String message = (String) body.getOrDefault("message", "");
        @SuppressWarnings("unchecked")
        List<String> images = (List<String>) body.get("images");

        Map<String, Object> request = new HashMap<>();
        request.put("session_id", sessionId);
        request.put("message", message);
        request.put("mode", "CHAT");
        request.put("stream", true);
        if (images != null && !images.isEmpty()) {
            request.put("images", images);
        }

        return webClient.post()
                .uri("/ai/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class);
    }
}
