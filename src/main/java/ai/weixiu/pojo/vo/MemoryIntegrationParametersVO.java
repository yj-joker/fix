package ai.weixiu.pojo.vo;

import ai.weixiu.entity.MemoryMessage;
import lombok.Data;

import java.util.List;
@Data
public class MemoryIntegrationParametersVO {
    private String sessionId; //会话ID
    private List<MemoryMessage> memoryMessages; //记忆消息 30条
    private List<MemoryPreferenceVO> memoryPreferenceVOList; //偏好记忆
    private List<MemoryUnresolvedVO> memoryUnresolvedVOList;//未完成摘要
}
