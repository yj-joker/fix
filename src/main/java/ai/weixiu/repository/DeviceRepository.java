package ai.weixiu.repository;

import ai.weixiu.entity.Device;

import ai.weixiu.pojo.vo.DeviceOverviewVO;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;


@Repository
public interface DeviceRepository extends Neo4jRepository<Device, String> {
    @Query("""
                MATCH (d:Device {id: $deviceId})
                OPTIONAL MATCH (d)-[:OWNS]->(c:Component)
                WITH d, count(DISTINCT c) AS componentCount
                OPTIONAL MATCH (d)-[:HAS_FAULT]->(f:Fault)
                RETURN d.id AS deviceId,
                       d.name AS deviceName,
                       d.code AS code,
                       d.model AS model,
                       d.location AS location,
                       d.manufacturer AS manufacturer,
                       componentCount,
                       count(DISTINCT f) AS faultCount
                """)
    Optional<DeviceOverviewVO> getDeviceOverview(@Param("deviceId") String deviceId);
}
