package ai.weixiu.service;

import ai.weixiu.entity.MemoryPreference;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 用户偏好记忆 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-12
 */
public interface MemoryPreferenceService extends IService<MemoryPreference> {

    List<MemoryPreference> getPreference(Long sessionId, Long userId);

    /**
     * 获取用户级偏好（跨会话有效，preferenceCategory = 0）
     *
     * @param userId 用户ID
     * @return 用户级偏好列表
     */
    List<MemoryPreference> getUserLevelPreferences(Long userId);
}
