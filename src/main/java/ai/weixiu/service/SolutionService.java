package ai.weixiu.service;

import ai.weixiu.entity.Solution;

import java.util.List;
import java.util.Optional;

public interface SolutionService {

    /**
     * 保存一个新的解决方案节点到 Neo4j
     *
     * @param solution 待保存的解决方案对象
     * @return 保存后的解决方案对象（含生成的 UUID）
     */
    Solution save(Solution solution);

    /**
     * 根据 ID 查询解决方案
     *
     * @param id 解决方案的唯一标识（UUID）
     * @return 包含解决方案对象的 Optional，若不存在则为 empty
     */
    Optional<Solution> findById(String id);

    /**
     * 查询所有解决方案节点
     *
     * @return 解决方案列表
     */
    List<Solution> findAll();

    /**
     * 根据 ID 删除解决方案节点
     *
     * @param id 待删除解决方案的唯一标识（UUID）
     */
    void deleteById(String id);

    /**
     * 更新解决方案信息（对象中需包含已有的 ID）
     *
     * @param solution 含更新内容的解决方案对象
     * @return 更新后的解决方案对象
     */
    Solution update(Solution solution);
}
