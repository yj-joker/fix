package ai.weixiu.service;

import ai.weixiu.pojo.entity.Component;

import java.util.List;
import java.util.Optional;

public interface ComponentService {

    /**
     * 保存一个新的部件节点到 Neo4j
     *
     * @param component 待保存的部件对象
     * @return 保存后的部件对象（含生成的 UUID）
     */
    Component save(Component component);

    /**
     * 根据 ID 查询部件
     *
     * @param id 部件的唯一标识（UUID）
     * @return 包含部件对象的 Optional，若不存在则为 empty
     */
    Optional<Component> findById(String id);

    /**
     * 查询所有部件节点
     *
     * @return 部件列表
     */
    List<Component> findAll();

    /**
     * 根据 ID 删除部件节点
     *
     * @param id 待删除部件的唯一标识（UUID）
     */
    void deleteById(String id);

    /**
     * 更新部件信息（对象中需包含已有的 ID）
     *
     * @param component 含更新内容的部件对象
     * @return 更新后的部件对象
     */
    Component update(Component component);
}
