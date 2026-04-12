package ai.weixiu.repository;

import ai.weixiu.pojo.entity.Device;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeviceRepository extends Neo4jRepository<Device, String> {
}
