package ai.weixiu.service.impl;

import ai.weixiu.entity.AiMessage;
import ai.weixiu.mapper.AiMessageMapper;
import ai.weixiu.service.AiMessageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-07
 */
@Service
@Slf4j
public class AiMessageServiceImpl extends ServiceImpl<AiMessageMapper, AiMessage> implements AiMessageService {
    @Override
    public List<AiMessage> findMemory(Long id, Long currentId, Integer maxMemory,Integer roundCount) {
        LambdaQueryWrapper<AiMessage> queryWrapper=new LambdaQueryWrapper<>();
        queryWrapper.eq(AiMessage::getUserId,currentId)
                .eq(AiMessage::getAiSessionId,id)
                .orderByAsc(AiMessage::getCreatedAt);
        queryWrapper.eq(AiMessage::getConsolidated, 0);
        if(roundCount>maxMemory){
            int start = roundCount%maxMemory;
            queryWrapper.between(AiMessage::getRoundNo,roundCount-start,roundCount);
        }
        List<AiMessage> list = this.list(queryWrapper);
        log.info("findMemory:{}",list.toString());
        return list;
    }

    @Override
    public List<AiMessage> getNeedIntegrationMemory(Integer roundCount, Long sessionId, Long userId, Integer maxMemory) {
        LambdaQueryWrapper<AiMessage> queryWrapper=new LambdaQueryWrapper<>();
        queryWrapper.eq(AiMessage::getUserId,userId)
                .eq(AiMessage::getAiSessionId,sessionId)
                .eq(AiMessage::getConsolidated, 0)
                .between(AiMessage::getRoundNo,roundCount-maxMemory,roundCount);
                return this.list(queryWrapper);
    }
}
