package ai.weixiu.controller;

import ai.weixiu.entity.*;
import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.vo.MemoryIntegrationParametersVO;
import ai.weixiu.pojo.vo.MemoryPreferenceVO;
import ai.weixiu.pojo.vo.MemoryUnresolvedVO;
import ai.weixiu.service.AiMessageService;
import ai.weixiu.service.AiSessionService;
import ai.weixiu.service.MemoryPreferenceService;
import ai.weixiu.service.MemoryUnresolvedService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/weixiu/memory")
@AllArgsConstructor
@Slf4j
@Tag(name = "记忆数据")
public class MemoryDataController {

    private final AiMessageService aiMessageService;
    private final AiSessionService aiSessionService;
    private final MemoryPreferenceService memoryPreferenceService;
    private final MemoryUnresolvedService memoryUnresolvedService;

    @GetMapping("/consolidation-params")
    @Operation(summary = "获取记忆整合参数（供Python消费者拉取）")
    public Result<MemoryIntegrationParametersVO> getConsolidationParams(
            @RequestParam Long sessionId,
            @RequestParam Long userId,
            @RequestParam Integer roundCount,
            @RequestParam Integer maxMemory) {

        List<AiMessage> messages = aiMessageService.getNeedIntegrationMemory(roundCount, sessionId, userId, maxMemory);
        if (messages.isEmpty()) {
            log.info("[记忆数据] 无需整合的消息, 会话ID:{}", sessionId);
            return Result.success(null);
        }

        // 信息密度检查
        int totalUserContentLength = 0;
        for (AiMessage msg : messages) {
            if ("user".equals(msg.getRole()) && msg.getContent() != null) {
                totalUserContentLength += msg.getContent().length();
            }
        }
        if (totalUserContentLength < 30) {
            log.info("[记忆数据] 用户消息总长度过短({}字符), 跳过, 会话ID:{}", totalUserContentLength, sessionId);
            return Result.success(null);
        }

        // 组装参数
        List<MemoryMessage> memoryMessages = new ArrayList<>();
        List<Long> messageIds = new ArrayList<>();
        for (AiMessage msg : messages) {
            MemoryMessage mm = new MemoryMessage();
            mm.setRole(msg.getRole());
            mm.setContent(msg.getContent());
            memoryMessages.add(mm);
            messageIds.add(msg.getId());
        }

        List<MemoryPreference> preferences = memoryPreferenceService.getPreference(sessionId, userId);
        List<MemoryPreferenceVO> prefVOs = new ArrayList<>();
        for (MemoryPreference p : preferences) {
            MemoryPreferenceVO vo = new MemoryPreferenceVO();
            vo.setContent(p.getContent());
            vo.setCategory(p.getCategory());
            vo.setPreferenceCategory(p.getPreferenceCategory());
            prefVOs.add(vo);
        }

        List<MemoryUnresolved> unresolved = memoryUnresolvedService.getUnresolved(sessionId);
        List<MemoryUnresolvedVO> unresolvedVOs = new ArrayList<>();
        for (MemoryUnresolved item : unresolved) {
            MemoryUnresolvedVO vo = new MemoryUnresolvedVO();
            vo.setId(item.getId());
            vo.setContent(item.getContent());
            vo.setType(item.getType());
            vo.setStatus(item.getStatus());
            unresolvedVOs.add(vo);
        }

        LambdaQueryWrapper<AiSession> sessionQuery = new LambdaQueryWrapper<>();
        sessionQuery.eq(AiSession::getId, sessionId);
        AiSession session = aiSessionService.getOne(sessionQuery);
        String previousSummary = session != null ? session.getSummary() : null;

        MemoryIntegrationParametersVO params = new MemoryIntegrationParametersVO();
        params.setSessionId(sessionId.toString());
        params.setMemoryMessages(memoryMessages);
        params.setMemoryPreferenceVOList(prefVOs);
        params.setMemoryUnresolvedVOList(unresolvedVOs);
        params.setPreviousSummary(previousSummary);
        params.setMessageIds(messageIds);

        return Result.success(params);
    }
}
