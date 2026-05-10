package ai.weixiu.repository;

import ai.weixiu.entity.Fault;
import ai.weixiu.pojo.vo.SolutionVO;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FaultRepository extends Neo4jRepository<Fault, String> {

    /*
    * 分页查询故障解决方案 - 数据列表
    * */
    @Query("""
        MATCH (f:Fault {id: $faultId})-[:HAS_SOLUTION]->(s:Solution)
        WHERE $solutionTitle IS NULL OR $solutionTitle = '' OR s.title CONTAINS $solutionTitle
        WITH s ORDER BY s.title SKIP $skip LIMIT $limit
        RETURN collect({
            id: s.id,
            code: s.code,
            title: s.title,
            description: s.description,
            toolsRequired: s.tools_required,
            estimatedTime: s.estimated_time,
            difficulty: s.difficulty,
            createdAt: s.created_at,
            verified: s.verified
        }) AS records
        """)
    List<SolutionVO> getSolutionRecords(
        @Param("faultId") String faultId,
        @Param("solutionTitle") String solutionTitle,
        @Param("skip") int skip,
        @Param("limit") int limit
    );

    /*
    * 分页查询故障解决方案 - 总数
    * */
    @Query("""
        MATCH (f:Fault {id: $faultId})-[:HAS_SOLUTION]->(s:Solution)
        WHERE $solutionTitle IS NULL OR $solutionTitle = '' OR s.title CONTAINS $solutionTitle
        RETURN count(s) AS total
        """)
    Long getSolutionTotal(
        @Param("faultId") String faultId,
        @Param("solutionTitle") String solutionTitle
    );
}
