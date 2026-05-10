package ai.weixiu.service.impl;

import ai.weixiu.pojo.vo.DeviceVO;
import ai.weixiu.pojo.vo.DiagnosisPathVO;
import ai.weixiu.repository.DeviceRepository;
import ai.weixiu.service.GraphQueryService;
import lombok.AllArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class GraphQueryServiceImpl implements GraphQueryService {
    private final Neo4jClient neo4jClient;
    private final DeviceRepository deviceRepository;
    /*
    * 根据设备id和故障，查询诊断路径
    * */
    @Override
    public List<DiagnosisPathVO> findDiagnosisPath(String keyword, String faultName, Long limit) {
        List<DiagnosisPathVO> diagnosisPaths= new ArrayList<>();
        List<DeviceVO> devices = deviceRepository.getDevices(keyword, 0, limit.intValue());
        devices.forEach(device -> {
            diagnosisPaths.addAll(getList(faultName, limit, device.getId()));
        });
        return diagnosisPaths;
    }

    private @NonNull List<DiagnosisPathVO> getList(String faultName, Long limit, String deviceId) {
        return neo4jClient.query("""
                        MATCH (d:Device {id: $deviceId})
                              -[:OWNS]->(c:Component)
                              -[:CAUSES]->(f:Fault)
                              -[:HAS_SOLUTION]->(s:Solution)
                        WHERE $faultName IS NULL
                           OR $faultName = ''
                           OR f.name CONTAINS $faultName
                        RETURN c.id AS componentId,
                               c.name AS componentName,
                               f.id AS faultId,
                               f.name AS faultName,
                               f.severity AS faultSeverity,
                               s.id AS solutionId,
                               s.title AS solutionTitle,
                               s.estimated_time AS estimatedTime,
                               s.verified AS verified
                        ORDER BY s.verified DESC, s.estimated_time ASC
                        LIMIT $limit
                        """)
                .bind(deviceId).to("deviceId")
                .bind(faultName).to("faultName")
                .bind(limit).to("limit")
                .fetchAs(DiagnosisPathVO.class)
                .mappedBy((typeSystem, record) -> {
                    DiagnosisPathVO vo = new DiagnosisPathVO();
                    vo.setComponentId(record.get("componentId").asString(null));
                    vo.setComponentName(record.get("componentName").asString(null));
                    vo.setFaultId(record.get("faultId").asString(null));
                    vo.setFaultName(record.get("faultName").asString(null));
                    vo.setFaultSeverity(record.get("faultSeverity").asString(null));
                    vo.setSolutionId(record.get("solutionId").asString(null));
                    vo.setSolutionTitle(record.get("solutionTitle").asString(null));
                    vo.setEstimatedTime(record.get("estimatedTime").isNull() ? null : record.get("estimatedTime").asInt());
                    vo.setVerified(record.get("verified").isNull() ? null : record.get("verified").asBoolean());
                    return vo;
                })
                .all()
                .stream()
                .toList();
    }


}
