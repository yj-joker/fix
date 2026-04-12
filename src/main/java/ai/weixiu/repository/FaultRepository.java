package ai.weixiu.repository;

import ai.weixiu.pojo.entity.Fault;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FaultRepository extends Neo4jRepository<Fault, String> {
}
