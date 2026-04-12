package ai.weixiu.repository;

import ai.weixiu.pojo.entity.Component;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ComponentRepository extends Neo4jRepository<Component, String> {
}
