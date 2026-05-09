package ai.weixiu.controller;

import ai.weixiu.entity.AiChatRequest;
import ai.weixiu.service.AiService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/weixiu/ai")
public class AiController {
    private final WebClient webClient;
    private final AiService aiService;
    public AiController(WebClient webClient, AiService aiService) {
        this.webClient = webClient;
        this.aiService = aiService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody AiChatRequest aiChatRequest) {
        return  aiService.chat(aiChatRequest);
    }
}
