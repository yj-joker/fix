package ai.weixiu.service;

import ai.weixiu.enumerate.MaintenanceManualRankType;
import ai.weixiu.pojo.vo.MaintenanceManualRankVO;

import java.util.List;

public interface MaintenanceManualRankService {

    void increaseRank(Long manualId);

    List<MaintenanceManualRankVO> getRankList(MaintenanceManualRankType rankType, Integer limit);
}
