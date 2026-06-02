package ai.weixiu.service;

import ai.weixiu.pojo.dto.GraphIngestDTO;

public interface GraphIngestService {
    /** 幂等入库：重复调用同一 manualId 不产生重复节点。返回处理的节点数。 */
    int ingestFromManual(GraphIngestDTO dto);

    /** 某手册关联某设备时，把该手册抽取的所有 Component 补 OWNS 边到该设备。返回补边的 Component 数。 */
    int linkManualComponentsToDevice(Long manualId, String deviceId);
}
