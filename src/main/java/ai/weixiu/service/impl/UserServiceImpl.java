package ai.weixiu.service.impl;

import ai.weixiu.pojo.entity.User;
import ai.weixiu.mapper.UserMapper;
import ai.weixiu.service.UserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 用户表 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-04-08
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

}
