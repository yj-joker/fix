package ai.weixiu.service;

import ai.weixiu.pojo.dto.GraphIngestDTO;

public interface GraphIngestService {
    /** 幂等入库：重复调用同一 manualId 不产生重复节点。返回处理的节点数。 */
    int ingestFromManual(GraphIngestDTO dto);
}
