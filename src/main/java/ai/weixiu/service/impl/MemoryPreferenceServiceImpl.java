package ai.weixiu.service.impl;

import ai.weixiu.entity.MemoryPreference;
import ai.weixiu.enumerate.PreferenceCategoryEnum;
import ai.weixiu.mapper.MemoryPreferenceMapper;
import ai.weixiu.service.MemoryPreferenceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * 用户偏好记忆 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-12
 */
@Service
public class MemoryPreferenceServiceImpl extends ServiceImpl<MemoryPreferenceMapper, MemoryPreference> implements MemoryPreferenceService {
    /*
    * 寻找合适的用户偏好
    * */
    @Override
    public List<MemoryPreference> getPreference(Long sessionId, Long userId) {
 LambdaQueryWrapper<MemoryPreference> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(MemoryPreference::getPreferenceCategory, PreferenceCategoryEnum.USER_PREFERENCE.getCategory())
                        .eq(MemoryPreference::getUserId, userId))
                .or(w -> w.eq(MemoryPreference::getPreferenceCategory, PreferenceCategoryEnum.SESSION_PREFERENCE.getCategory())
                        .eq(MemoryPreference::getSessionId, sessionId));
        return this.list(wrapper);
    }

    @Override
    public List<MemoryPreference> getUserLevelPreferences(Long userId) {
        LambdaQueryWrapper<MemoryPreference> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MemoryPreference::getUserId, userId)
                .eq(MemoryPreference::getPreferenceCategory, PreferenceCategoryEnum.USER_PREFERENCE.getCategory());
        return this.list(wrapper);
    }
}
