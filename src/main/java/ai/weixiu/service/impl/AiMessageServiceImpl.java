package ai.weixiu.service.impl;

import ai.weixiu.entity.AiMessage;
import ai.weixiu.mapper.AiMessageMapper;
import ai.weixiu.service.AiMessageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
public class AiMessageServiceImpl extends ServiceImpl<AiMessageMapper, AiMessage> implements AiMessageService {
    @Override
    public List<AiMessage> findMemory(Long id, Long currentId, Integer maxMemory,Integer roundCount) {
        LambdaQueryWrapper<AiMessage> queryWrapper=new LambdaQueryWrapper<>();
        queryWrapper.eq(AiMessage::getUserId,currentId)
                .eq(AiMessage::getAiSessionId,id)
                .orderByAsc(AiMessage::getCreatedAt);
        if(roundCount>maxMemory){
            queryWrapper.between(AiMessage::getRoundNo,roundCount-maxMemory,roundCount);
        }
        return this.list(queryWrapper);
    }
}
