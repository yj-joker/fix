package ai.weixiu.repository;

import ai.weixiu.entity.CaseRecord;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CaseRecordRepository extends Neo4jRepository<CaseRecord, String> {

    @Query("MATCH (c:CaseRecord) WHERE c.status = $status " +
            "RETURN c ORDER BY c.recorded_at DESC SKIP $skip LIMIT $size")
    List<CaseRecord> findByStatus(@Param("status") String status,
                                  @Param("skip") long skip,
                                  @Param("size") int size);

    @Query("MATCH (c:CaseRecord) WHERE c.status = $status RETURN count(c)")
    long countByStatus(@Param("status") String status);

    @Query("MATCH (c:CaseRecord) WHERE c.submitted_by_id = $uid " +
            "RETURN c ORDER BY c.recorded_at DESC SKIP $skip LIMIT $size")
    List<CaseRecord> findBySubmittedBy(@Param("uid") Long uid,
                                       @Param("skip") long skip,
                                       @Param("size") int size);

    @Query("MATCH (c:CaseRecord) WHERE c.submitted_by_id = $uid RETURN count(c)")
    long countBySubmittedBy(@Param("uid") Long uid);
}
