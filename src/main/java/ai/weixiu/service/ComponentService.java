package ai.weixiu.service;

import ai.weixiu.entity.Component;
import ai.weixiu.pojo.dto.ComponentDTO;

import java.util.List;
import java.util.Optional;

public interface ComponentService {

    /**
     * 新增部件
     */
    Component save(ComponentDTO componentDTO);

    /**
     * 根据 ID 查询部件
     */
    Optional<Component> findById(String id);

    /**
     * 查询所有部件节点
     */
    List<Component> findAll();

    /**
     * 根据 ID 删除部件节点
     */
    void deleteById(String id);

    /**
     * 更新部件信息
     */
    Component update(ComponentDTO componentDTO);

}
