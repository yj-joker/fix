package ai.weixiu.repository;

import ai.weixiu.entity.Device;
import ai.weixiu.pojo.vo.ComponentVO;
import ai.weixiu.pojo.vo.DeviceOverviewVO;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends Neo4jRepository<Device, String> {

    /*
    * 获取设备概览信息
    * */
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

    /*
    * 分页获取部件 - 数据列表
    * */
    @Query("""
        MATCH (d:Device {id: $deviceId})-[:OWNS]->(c:Component)
        WHERE $componentName IS NULL OR $componentName = '' OR c.name CONTAINS $componentName
        WITH c ORDER BY c.name SKIP $skip LIMIT $limit
        RETURN collect({
            id: c.id,
            name: c.name,
            partNumber: c.part_number,
            specification: c.specification,
            supplier: c.supplier,
            lifecycle: c.lifecycle,
            unitPrice: c.unit_price
        }) AS records
        """)
    List<ComponentVO> getComponentRecords(
        @Param("deviceId") String deviceId,
        @Param("componentName") String componentName,
        @Param("skip") int skip,
        @Param("limit") int limit
    );

    /*
    * 分页获取部件 - 总数
    * */
    @Query("""
        MATCH (d:Device {id: $deviceId})-[:OWNS]->(c:Component)
        WHERE $componentName IS NULL OR $componentName = '' OR c.name CONTAINS $componentName
        RETURN count(c) AS total
        """)
    Long getComponentTotal(
        @Param("deviceId") String deviceId,
        @Param("componentName") String componentName
    );
}