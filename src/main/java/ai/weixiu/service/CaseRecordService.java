package ai.weixiu.service;

import ai.weixiu.entity.CaseRecord;
import ai.weixiu.pojo.dto.CaseRecordDTO;
import ai.weixiu.pojo.vo.CaseDraftVO;

import java.util.List;
import java.util.Optional;

public interface CaseRecordService {

    /**
     * 从已关闭的检修任务起草案例草稿（调 AI 起草，不落库）。
     *
     * @param taskId 来源检修任务ID（任务须为 CLOSED）
     * @return 案例草稿（含任务带入的 deviceId/deviceName/faultName/imageUrls）
     */
    CaseDraftVO draftFromTask(Long taskId);

    /**
     * 新增案例记录
     */
    CaseRecord save(CaseRecordDTO caseRecordDTO);

    /**
     * 根据 ID 查询案例记录
     */
    Optional<CaseRecord> findById(String id);

    /**
     * 查询所有案例记录节点
     */
    List<CaseRecord> findAll();

    /**
     * 根据 ID 删除案例记录节点
     */
    void deleteById(String id);

    /**
     * 更新案例记录信息
     */
    CaseRecord update(CaseRecordDTO caseRecordDTO);

}
