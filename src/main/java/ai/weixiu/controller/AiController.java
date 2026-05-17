package ai.weixiu.controller;

import ai.weixiu.entity.AiChatRequest;
import ai.weixiu.pojo.Result;
import ai.weixiu.service.AiService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/weixiu/ai")
public class AiController {
    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary ="AI对话")
    public Flux<String> chat(@RequestBody AiChatRequest aiChatRequest) {
        return aiService.chat(aiChatRequest);
    }

    /*
     * 语音输入功能,实现语音->文本转换，并返回给前端(本地部署大模型)
     * */
//    @PostMapping("/transcribe")
//    @Operation(summary ="语音输入")
//    public Result<String> transcribe(MultipartFile file) {
//        return Result.success(aiService.getStringByVoiceViaLLM(file));
//    }

    /*
    * 语音输入功能,实现语音->文本转换，并返回给前端(调用百度的服务)
    * */
    @PostMapping("/transcribeByBaiDu")
    @Operation(summary ="语音输入")
    public Result<String> transcribeByBaiDu(MultipartFile file) {
        return Result.success(aiService.getStringByVoiceViaBaidu(file));
    }
}
