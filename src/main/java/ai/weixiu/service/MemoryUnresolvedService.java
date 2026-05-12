package ai.weixiu.service;

import ai.weixiu.entity.MemoryUnresolved;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 未完成事项记忆 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-12
 */
public interface MemoryUnresolvedService extends IService<MemoryUnresolved> {

    List<MemoryUnresolved> getUnresolved(Long sessionId);
}
