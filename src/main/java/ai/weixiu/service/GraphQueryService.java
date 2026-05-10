package ai.weixiu.service;

import ai.weixiu.pojo.vo.DiagnosisPathVO;

import java.util.List;

public interface GraphQueryService {
    List<DiagnosisPathVO> findDiagnosisPath(String keyword, String faultName,Long limit);
}
