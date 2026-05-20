package ai.weixiu.service;

import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.query.DiagnosisSearchQuery;
import ai.weixiu.pojo.vo.DiagnosisPathVO;

public interface GraphQueryService {

    /**
     * 统一诊断路径查询
     * <p>
     * 根据 keyword（设备模糊匹配）、faultDescription（故障向量）、
     * componentDescription（部件向量）、imageUrls（图片向量）分别检索，
     * ID 层面合并去重后，通过 OR 匹配 + 多维度评分排序返回路径。
     */
    PageResult<DiagnosisPathVO> searchDiagnosisPaths(DiagnosisSearchQuery query);
}
