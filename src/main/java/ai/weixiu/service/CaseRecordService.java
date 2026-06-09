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
     * 提交案例（合规闸门 → 落 pending 待审）。
     * <p>合规 LLM 判定不通过时抛业务异常，拦截提交；通过则以 status=pending 落库（暂不向量化）。</p>
     *
     * @param dto 老师傅修改后的案例草稿 + 来源/锚定线索
     */
    void submit(CaseRecordDTO dto);

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
