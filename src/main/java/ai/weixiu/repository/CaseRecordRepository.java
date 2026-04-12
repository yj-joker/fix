package ai.weixiu.repository;

import ai.weixiu.pojo.entity.CaseRecord;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CaseRecordRepository extends Neo4jRepository<CaseRecord, String> {
}
