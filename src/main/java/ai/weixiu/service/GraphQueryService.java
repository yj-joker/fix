package ai.weixiu.service;

import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.vo.DiagnosisPathVO;

public interface GraphQueryService {

    /**
     * 分页查询诊断路径
     */
    PageResult<DiagnosisPathVO> findDiagnosisPaths(String keyword, String faultDescription, int page, int size);
}