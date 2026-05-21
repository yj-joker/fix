package ai.weixiu.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AiChatRequest {
    @JsonProperty("session_id")
    private String sessionId;

    /**
     * 当前用户消息（纯文本）
     */
    @JsonProperty("message")
    private String userMessage;

    /**
     * 多轮对话历史，格式：[{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]
     * Python端据此构建OpenAI多轮消息，让LLM正确区分用户和助手发言
     */
    @JsonProperty("conversation_history")
    private List<Map<String, String>> conversationHistory;

    /**
     * 结构化上下文信息（摘要、事实、偏好、待办）
     * Python端将其注入system prompt或作为辅助上下文
     */
    @JsonProperty("context")
    private Map<String, Object> context;

    /**
     * 用户上传的图片 URL 列表（MinIO 地址）
     * Python 端 FixAgent 会将图片传给图谱查询工具做多模态检索
     */
    @JsonProperty("images")
    private List<String> images;
}
