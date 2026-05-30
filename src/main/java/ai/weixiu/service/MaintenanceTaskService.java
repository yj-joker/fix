package ai.weixiu.service;

import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.dto.MaintenanceTaskDTO;
import ai.weixiu.pojo.dto.StepExecuteDTO;
import ai.weixiu.pojo.query.MaintenanceTaskQuery;
import ai.weixiu.pojo.vo.MaintenanceTaskVO;
import ai.weixiu.pojo.vo.TaskStepRecordVO;

import java.util.List;
import java.util.Map;

public interface MaintenanceTaskService {

    /** 创建任务并异步触发LLM生成步骤 */
    MaintenanceTaskVO createTask(MaintenanceTaskDTO dto, Long reporterId);

    /** 重试生成（GENERATE_FAILED → GENERATING） */
    void retryGenerate(Long taskId);

    /** 开始执行（GENERATED → EXECUTING） */
    void startExecute(Long taskId);

    /** 执行某一步骤（提交证据 → AI验证） */
    TaskStepRecordVO executeStep(Long taskId, Long stepId, StepExecuteDTO dto);

    /** 查询任务详情（含步骤列表） */
    MaintenanceTaskVO getTaskDetail(Long taskId);

    /** 分页查询任务列表 */
    PageResult<MaintenanceTaskVO> listTasks(MaintenanceTaskQuery query);

    /** 查询任务的步骤列表 */
    List<TaskStepRecordVO> listSteps(Long taskId);

    /** MQ回调：LLM生成步骤成功（含图谱线索） */
    void onGenerateSuccess(Long taskId, List<TaskStepRecordVO> steps, Object graphExtraction);

    /** MQ回调：LLM生成步骤失败 */
    void onGenerateFailed(Long taskId, String errorMsg);

    /** 沉淀为标准规程（CLOSED → 创建 StandardProcedure） */
    Long promoteToStandardProcedure(Long taskId, Long operatorId);

    /** 沉淀到知识图谱（CLOSED → 创建图谱节点） */
    void promoteToGraph(Long taskId, Map<String, Object> graphData);
}
