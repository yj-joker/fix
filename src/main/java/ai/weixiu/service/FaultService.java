package ai.weixiu.service;

import ai.weixiu.entity.Fault;
import ai.weixiu.pojo.dto.FaultDTO;

import java.util.List;
import java.util.Optional;

public interface FaultService {

    /**
     * 新增故障
     */
    Fault save(FaultDTO faultDTO);

    /**
     * 根据 ID 查询故障
     */
    Optional<Fault> findById(String id);

    /**
     * 查询所有故障节点
     */
    List<Fault> findAll();

    /**
     * 根据 ID 删除故障节点
     */
    void deleteById(String id);

    /**
     * 更新故障信息
     */
    Fault update(FaultDTO faultDTO);

}
