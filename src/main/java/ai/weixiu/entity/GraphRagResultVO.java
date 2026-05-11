package ai.weixiu.entity;

import ai.weixiu.pojo.vo.DiagnosisPathVO;
import ai.weixiu.pojo.vo.FaultVO;
import lombok.Data;

import java.util.List;

@Data
public class GraphRagResultVO {
    private String question;
    private String deviceKeyword;
    private List<FaultVO> matchedFaults;
    private List<DiagnosisPathVO> diagnosisPaths;
    private String context;
}
