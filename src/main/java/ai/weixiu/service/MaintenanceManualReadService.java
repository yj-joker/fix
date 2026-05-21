package ai.weixiu.service;

import ai.weixiu.pojo.vo.MaintenanceManualReadHeartbeatVO;
import ai.weixiu.pojo.vo.MaintenanceManualReadStartVO;

public interface MaintenanceManualReadService {

    MaintenanceManualReadStartVO start(Long manualId);

    MaintenanceManualReadHeartbeatVO heartbeat(String readSessionId);
}
