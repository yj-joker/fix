package ai.weixiu.service;

import ai.weixiu.entity.Device;

import java.util.List;
import java.util.Optional;

public interface DeviceService {

    /**
     * 保存一个新的设备节点到 Neo4j
     *
     * @param device 待保存的设备对象
     * @return 保存后的设备对象（含生成的 UUID）
     */
    Device save(Device device);

    /**
     * 根据 ID 查询设备
     *
     * @param id 设备的唯一标识（UUID）
     * @return 包含设备对象的 Optional，若不存在则为 empty
     */
    Optional<Device> findById(String id);

    /**
     * 查询所有设备节点
     *
     * @return 设备列表
     */
    List<Device> findAll();

    /**
     * 根据 ID 删除设备节点
     *
     * @param id 待删除设备的唯一标识（UUID）
     */
    void deleteById(String id);

    /**
     * 更新设备信息（对象中需包含已有的 ID）
     *
     * @param device 含更新内容的设备对象
     * @return 更新后的设备对象
     */
    Device update(Device device);
}
