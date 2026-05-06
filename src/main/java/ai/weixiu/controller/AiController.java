package ai.weixiu.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/weixiu/ai")
public class AiController {
    @PostMapping("/chat")
    public String chat(String message) {
        return "hello world";
    }
}
