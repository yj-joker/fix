package ai.weixiu.service;

import ai.weixiu.entity.AiChatRequest;
import reactor.core.publisher.Flux;

public interface AiService {
    Flux<String> chat(AiChatRequest request);

}
