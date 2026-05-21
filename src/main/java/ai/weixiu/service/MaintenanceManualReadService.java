package ai.weixiu.service;

import ai.weixiu.pojo.vo.MaintenanceManualReadHeartbeatVO;
import ai.weixiu.pojo.vo.MaintenanceManualReadStartVO;

public interface MaintenanceManualReadService {

    /**
     * 为当前登录用户创建一次手册阅读会话。
     *
     * @param manualId 要阅读的手册 id
     * @return 会话 id、建议心跳间隔和有效阅读阈值
     */
    MaintenanceManualReadStartVO start(Long manualId);

    /**
     * 接收前端阅读心跳，累计当前会话产生的有效阅读秒数。
     *
     * @param readSessionId start 接口返回的阅读会话 id
     * @return 当前当天累计阅读秒数以及是否已计榜
     */
    MaintenanceManualReadHeartbeatVO heartbeat(String readSessionId);
}
