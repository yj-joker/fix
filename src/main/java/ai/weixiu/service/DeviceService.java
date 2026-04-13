package ai.weixiu.service;

import ai.weixiu.entity.Device;
import ai.weixiu.pojo.dto.DeviceDTO;

import java.util.List;
import java.util.Optional;

public interface DeviceService {

    /**
     * 新增设备
     */
    Device save(DeviceDTO deviceDTO);

    /**
     * 根据 ID 查询设备
     */
    Optional<Device> findById(String id);

    /**
     * 查询所有设备节点
     */
    List<Device> findAll();

    /**
     * 根据 ID 删除设备节点
     */
    void deleteById(String id);

    /**
     * 更新设备信息
     */
    Device update(DeviceDTO deviceDTO);

}
