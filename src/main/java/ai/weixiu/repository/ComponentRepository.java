package ai.weixiu.repository;

import ai.weixiu.entity.Component;
import ai.weixiu.pojo.vo.FaultVO;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComponentRepository extends Neo4jRepository<Component, String> {

    /*
    * 分页查询部件故障 - 数据列表
    * */
    @Query("""
        MATCH (c:Component {id: $componentId})-[:CAUSES]->(f:Fault)
        WHERE $faultName IS NULL OR $faultName = '' OR f.name CONTAINS $faultName
        WITH f ORDER BY f.name SKIP $skip LIMIT $limit
        RETURN collect({
            id: f.id,
            code: f.code,
            name: f.name,
            description: f.description,
            severity: f.severity,
            category: f.category,
            occurrenceTime: f.occurrence_time,
            reportedBy: f.reported_by
        }) AS records
        """)
    List<FaultVO> getFaultRecords(
        @Param("componentId") String componentId,
        @Param("faultName") String faultName,
        @Param("skip") int skip,
        @Param("limit") int limit
    );

    /*
    * 分页查询部件故障 - 总数
    * */
    @Query("""
        MATCH (c:Component {id: $componentId})-[:CAUSES]->(f:Fault)
        WHERE $faultName IS NULL OR $faultName = '' OR f.name CONTAINS $faultName
        RETURN count(f) AS total
        """)
    Long getFaultTotal(
        @Param("componentId") String componentId,
        @Param("faultName") String faultName
    );
}
