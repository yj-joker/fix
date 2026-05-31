package ai.weixiu.controller;

import ai.weixiu.annotation.RequireAdmin;
import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.dto.MaintenanceTaskDTO;
import ai.weixiu.pojo.dto.StepExecuteDTO;
import ai.weixiu.pojo.query.MaintenanceTaskQuery;
import ai.weixiu.pojo.vo.MaintenanceTaskVO;
import ai.weixiu.pojo.vo.TaskStepRecordVO;
import ai.weixiu.entity.User;
import ai.weixiu.mapper.UserMapper;
import ai.weixiu.service.MaintenanceTaskService;
import ai.weixiu.utils.BaseContext;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "检修任务管理")
public class MaintenanceTaskController {

    private final MaintenanceTaskService taskService;
    private final UserMapper userMapper;
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

    /** 工人强制完成步骤（AI验证未通过时，工人确认无误可强制完成） */
    @PostMapping("/{taskId}/steps/{stepId}/force-complete")
    public Result<TaskStepRecordVO> forceCompleteStep(
            @PathVariable Long taskId,
            @PathVariable Long stepId,
            @RequestBody Map<String, Object> body) {
        String reason = (String) body.getOrDefault("reason", "");
        TaskStepRecordVO vo = taskService.forceCompleteStep(taskId, stepId, reason);
        return Result.success(vo);
    }

    /** 查询任务详情（含步骤列表） */
    @GetMapping("/{taskId}")
    public Result<MaintenanceTaskVO> getTaskDetail(@PathVariable Long taskId) {
        MaintenanceTaskVO vo = taskService.getTaskDetail(taskId);
        return Result.success(vo);
    }

    /** 分页查询任务列表（员工只看自己的，管理员看全部，支持沉淀状态筛选） */
    @GetMapping
    public Result<PageResult<MaintenanceTaskVO>> listTasks(MaintenanceTaskQuery query) {
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.selectById(userId);
        Integer userType = (user != null) ? user.getType() : 0;
        PageResult<MaintenanceTaskVO> result = taskService.listTasks(query, userId, userType);
        return Result.success(result);
    }

    /** 查询任务的步骤列表 */
    @GetMapping("/{taskId}/steps")
    public Result<List<TaskStepRecordVO>> listSteps(@PathVariable Long taskId) {
        List<TaskStepRecordVO> steps = taskService.listSteps(taskId);
        return Result.success(steps);
    }

    /** 沉淀为标准规程（CLOSED → 创建 StandardProcedure，返回规程ID） */
    @RequireAdmin
    @PostMapping("/{taskId}/promote")
    public Result<Long> promoteToStandardProcedure(@PathVariable Long taskId) {
        Long operatorId = BaseContext.getCurrentId();
        Long procedureId = taskService.promoteToStandardProcedure(taskId, operatorId);
        return Result.success(procedureId);
    }

    /** 沉淀到知识图谱（CLOSED → 创建图谱节点，管理员确认后提交） */
    @RequireAdmin
    @PostMapping("/{taskId}/promote-to-graph")
    public Result<Void> promoteToGraph(
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> graphData) {
        taskService.promoteToGraph(taskId, graphData);
        return Result.success(null);
    }

    /** 管理员跳过沉淀（标记为无沉淀价值） */
    @RequireAdmin
    @PostMapping("/{taskId}/skip-promotion")
    public Result<Void> skipPromotion(
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> body) {
        String type = (String) body.getOrDefault("type", "both");
        taskService.skipPromotion(taskId, type);
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
