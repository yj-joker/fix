package ai.weixiu.service;

import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.query.MultimodalSearchQuery;
import ai.weixiu.pojo.vo.DiagnosisPathVO;

public interface GraphQueryService {

    /**
     * 分页查询诊断路径
     */
    PageResult<DiagnosisPathVO> findDiagnosisPaths(String keyword,String ComponentDescription ,String faultDescription, int page, int size);

    /**
     * 通过多模态（文字+图片）融合向量检索诊断路径
     */
    PageResult<DiagnosisPathVO> findDiagnosisPathsByMultimodal(MultimodalSearchQuery query);
}