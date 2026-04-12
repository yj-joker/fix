package ai.weixiu.service;

import ai.weixiu.entity.CaseRecord;

import java.util.List;
import java.util.Optional;

public interface CaseRecordService {

    /**
     * 保存一个新的案例记录节点到 Neo4j
     *
     * @param caseRecord 待保存的案例记录对象
     * @return 保存后的案例记录对象（含生成的 UUID）
     */
    CaseRecord save(CaseRecord caseRecord);

    /**
     * 根据 ID 查询案例记录
     *
     * @param id 案例记录的唯一标识（UUID）
     * @return 包含案例记录对象的 Optional，若不存在则为 empty
     */
    Optional<CaseRecord> findById(String id);

    /**
     * 查询所有案例记录节点
     *
     * @return 案例记录列表
     */
    List<CaseRecord> findAll();

    /**
     * 根据 ID 删除案例记录节点
     *
     * @param id 待删除案例记录的唯一标识（UUID）
     */
    void deleteById(String id);

    /**
     * 更新案例记录信息（对象中需包含已有的 ID）
     *
     * @param caseRecord 含更新内容的案例记录对象
     * @return 更新后的案例记录对象
     */
    CaseRecord update(CaseRecord caseRecord);
}
