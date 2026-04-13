package ai.weixiu.service;

import ai.weixiu.entity.CaseRecord;
import ai.weixiu.pojo.dto.CaseRecordDTO;

import java.util.List;
import java.util.Optional;

public interface CaseRecordService {

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
