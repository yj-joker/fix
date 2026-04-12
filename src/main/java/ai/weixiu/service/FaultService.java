package ai.weixiu.service;

import ai.weixiu.pojo.entity.Fault;

import java.util.List;
import java.util.Optional;

public interface FaultService {

    /**
     * 保存一个新的故障节点到 Neo4j
     *
     * @param fault 待保存的故障对象
     * @return 保存后的故障对象（含生成的 UUID）
     */
    Fault save(Fault fault);

    /**
     * 根据 ID 查询故障
     *
     * @param id 故障的唯一标识（UUID）
     * @return 包含故障对象的 Optional，若不存在则为 empty
     */
    Optional<Fault> findById(String id);

    /**
     * 查询所有故障节点
     *
     * @return 故障列表
     */
    List<Fault> findAll();

    /**
     * 根据 ID 删除故障节点
     *
     * @param id 待删除故障的唯一标识（UUID）
     */
    void deleteById(String id);

    /**
     * 更新故障信息（对象中需包含已有的 ID）
     *
     * @param fault 含更新内容的故障对象
     * @return 更新后的故障对象
     */
    Fault update(Fault fault);
}
