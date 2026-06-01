package ai.weixiu.service.impl;

import ai.weixiu.entity.MemoryUnresolved;
import ai.weixiu.mapper.MemoryUnresolvedMapper;
import ai.weixiu.service.MemoryUnresolvedService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * 未完成事项记忆 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-12
 */
@Service
public class MemoryUnresolvedServiceImpl extends ServiceImpl<MemoryUnresolvedMapper, MemoryUnresolved> implements MemoryUnresolvedService {
    @Override
    public List<MemoryUnresolved> getUnresolved(Long sessionId) {
        LambdaQueryWrapper<MemoryUnresolved> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MemoryUnresolved::getSessionId, sessionId)
                .eq(MemoryUnresolved::getStatus, "active");
        return this.list(wrapper);
    }
}