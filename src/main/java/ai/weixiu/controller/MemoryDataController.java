package ai.weixiu.controller;

import ai.weixiu.entity.*;
import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.vo.MemoryIntegrationParametersVO;
import ai.weixiu.pojo.vo.MemoryPreferenceVO;
import ai.weixiu.pojo.vo.MemoryUnresolvedVO;
import ai.weixiu.pojo.vo.RecallDetailVO;
import ai.weixiu.service.AiMessageService;
import ai.weixiu.service.AiSessionService;
import ai.weixiu.service.MemoryFactService;
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
    private final MemoryFactService memoryFactService;
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

    /**
     * 细节召回接口（供 Python FixAgent 的 recall_conversation_detail 工具调用）
     *
     * 流程：
     * 1. 用 keywords 模糊匹配 MemoryFact 的 content 和 keywords 字段
     * 2. 取匹配到的事实的 sourceSeqRange（如 "3-5"）
     * 3. 用 sessionId + roundNo 范围查询 AiMessage 获取原始对话
     * 4. 返回事实摘要 + 原始消息列表
     */
    @GetMapping("/recall-detail")
    @Operation(summary = "召回事实关联的原始对话细节（供Python工具调用）")
    public Result<List<RecallDetailVO>> recallDetail(
            @RequestParam String keywords,
            @RequestParam Long userId,
            @RequestParam(defaultValue = "3") Integer maxFacts) {

        // 1. 查出该用户所有会话ID
        LambdaQueryWrapper<AiSession> sessionQuery = new LambdaQueryWrapper<>();
        sessionQuery.eq(AiSession::getUserId, userId).select(AiSession::getId);
        List<Long> userSessionIds = aiSessionService.list(sessionQuery)
                .stream().map(AiSession::getId).toList();

        if (userSessionIds.isEmpty()) {
            return Result.success(List.of());
        }

        // 2. 模糊匹配 MemoryFact（content 或 keywords 包含关键词）
        LambdaQueryWrapper<MemoryFact> factQuery = new LambdaQueryWrapper<>();
        factQuery.in(MemoryFact::getSessionId, userSessionIds.stream().map(String::valueOf).toList())
                .eq(MemoryFact::getStatus, "active")
                .isNotNull(MemoryFact::getSourceSeqRange)
                .and(w -> w.like(MemoryFact::getContent, keywords)
                        .or()
                        .like(MemoryFact::getKeywords, keywords))
                .last("LIMIT " + maxFacts);

        List<MemoryFact> matchedFacts = memoryFactService.list(factQuery);
        if (matchedFacts.isEmpty()) {
            log.info("[细节召回] 未找到匹配事实, keywords={}, userId={}", keywords, userId);
            return Result.success(List.of());
        }

        // 3. 逐个事实召回原始消息
        List<RecallDetailVO> results = new ArrayList<>();
        for (MemoryFact fact : matchedFacts) {
            String seqRange = fact.getSourceSeqRange();
            if (seqRange == null || seqRange.isBlank()) continue;

            // 解析 sourceSeqRange，支持三种格式：
            //   "3"     → 单轮 → [(3,3)]
            //   "3-5"   → 连续范围 → [(3,5)]
            //   "3-5,9" → 多段（含纠正轮次）→ [(3,5), (9,9)]
            List<int[]> segments = parseSeqRange(seqRange);
            if (segments.isEmpty()) {
                log.warn("[细节召回] 无法解析 sourceSeqRange={}, factId={}", seqRange, fact.getId());
                continue;
            }

            // 查询该事实所属会话的原始消息（多段用 OR 拼接）
            Long sessionId = Long.valueOf(fact.getSessionId());
            LambdaQueryWrapper<AiMessage> msgQuery = new LambdaQueryWrapper<>();
            msgQuery.eq(AiMessage::getAiSessionId, sessionId)
                    .in(AiMessage::getRole, List.of("user", "assistant"))
                    .and(outer -> {
                        for (int i = 0; i < segments.size(); i++) {
                            int[] seg = segments.get(i);
                            if (i == 0) {
                                outer.between(AiMessage::getRoundNo, seg[0], seg[1]);
                            } else {
                                outer.or().between(AiMessage::getRoundNo, seg[0], seg[1]);
                            }
                        }
                    })
                    .orderByAsc(AiMessage::getRoundNo)
                    .orderByAsc(AiMessage::getId);

            List<AiMessage> messages = aiMessageService.list(msgQuery);

            RecallDetailVO vo = new RecallDetailVO();
            vo.setFactContent(fact.getContent());
            vo.setSourceSeqRange(seqRange);

            List<RecallDetailVO.MessageItem> items = new ArrayList<>();
            for (AiMessage msg : messages) {
                RecallDetailVO.MessageItem item = new RecallDetailVO.MessageItem();
                item.setRole(msg.getRole());
                item.setContent(msg.getContent());
                item.setRoundNo(msg.getRoundNo());
                items.add(item);
            }
            vo.setMessages(items);
            results.add(vo);
        }

        log.info("[细节召回] 命中{}条事实, 召回{}条结果, keywords={}", matchedFacts.size(), results.size(), keywords);
        return Result.success(results);
    }

    /**
     * 解析 sourceSeqRange 字符串为多段 [start, end] 列表
     *
     * 支持格式：
     *   "3"       → [(3,3)]           单轮
     *   "3-5"     → [(3,5)]           连续范围
     *   "3-5,9"   → [(3,5),(9,9)]     多段（原始讨论 + 纠正轮次）
     *   "3-5,9-11"→ [(3,5),(9,11)]    多段连续范围
     *
     * 生成的 SQL 效果：
     *   单段 "3-5"       → WHERE round_no BETWEEN 3 AND 5
     *   多段 "3-5,9"     → WHERE (round_no BETWEEN 3 AND 5) OR (round_no BETWEEN 9 AND 9)
     *   多段 "3-5,9-11"  → WHERE (round_no BETWEEN 3 AND 5) OR (round_no BETWEEN 9 AND 11)
     */
    private List<int[]> parseSeqRange(String seqRange) {
        List<int[]> segments = new ArrayList<>();
        try {
            // 按逗号分割多段
            String[] parts = seqRange.split(",");
            for (String part : parts) {
                part = part.trim();
                if (part.isEmpty()) continue;

                if (part.contains("-")) {
                    // 范围段："3-5"
                    String[] range = part.split("-");
                    int start = Integer.parseInt(range[0].trim());
                    int end = Integer.parseInt(range[1].trim());
                    segments.add(new int[]{start, end});
                } else {
                    // 单轮段："9"
                    int single = Integer.parseInt(part);
                    segments.add(new int[]{single, single});
                }
            }
        } catch (NumberFormatException e) {
            log.warn("[细节召回] sourceSeqRange 格式异常: {}", seqRange);
        }
        return segments;
    }
}
