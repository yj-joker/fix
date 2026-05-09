package ai.weixiu.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.File;

@Data
public class AiChatRequest {
    @JsonProperty("session_id")
    private String sessionId;
    @JsonProperty("message")
    private String userMessage;

    private String url;
}

