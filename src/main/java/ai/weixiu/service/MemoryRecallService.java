package ai.weixiu.service;

import ai.weixiu.pojo.dto.RecallContext;

/**
 * 记忆召回服务 — 统一封装所有记忆类型的召回逻辑。
 *
 * <p>从 AiServiceImpl.finalAiContext() 中抽出，统一管理：
 * summary、relevantFacts、preferences、unresolved 四类召回，
 * 并记录每次召回的完整 trace。</p>
 */
public interface MemoryRecallService {

    /**
     * 执行完整的记忆召回。
     *
     * @param sessionId    会话ID
     * @param userId       用户ID
     * @param userMessage  用户当前消息（用于事实向量检索）
     * @param roundNo      当前对话轮次（用于 trace 记录）
     * @return 包含所有召回数据和 trace 的上下文对象
     */
    RecallContext recall(Long sessionId, Long userId, String userMessage, Integer roundNo);
}
