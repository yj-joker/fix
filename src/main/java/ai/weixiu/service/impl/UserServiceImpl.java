package ai.weixiu.service.impl;

import ai.weixiu.exceprion.NameOrPasswordException;
import ai.weixiu.exceprion.NullException;
import ai.weixiu.pojo.dto.UserDTO;
import ai.weixiu.pojo.entity.User;
import ai.weixiu.mapper.UserMapper;
import ai.weixiu.pojo.vo.UserVO;
import ai.weixiu.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 用户表 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-04-08
 */
@Service
@AllArgsConstructor
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final RedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserVO login(UserDTO userDTO, HttpServletRequest httpServletRequest) {

        if (userDTO == null) {
            log.error("用户信息不能为空");
            throw new NullException("用户信息不能为空");
        }
        // 查询用户
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, userDTO.getUsername());
        User user = this.getOne(queryWrapper);
        // 用户不存在
        if (user == null) {
            log.error("用户不存在");
            throw new NameOrPasswordException("用户名或密码错误");
        } else {
            //进行bcrypt密码校验
            if (!passwordEncoder.matches(userDTO.getPassword(), user.getPassword())) {
                log.error("密码错误");
                throw new NameOrPasswordException("用户名或密码错误");
            }
        }
        // 登录成功,设置最后登录时间
        user.setLastLoginTime(LocalDateTime.now());
        this.updateById(user);
        HttpSession httpSession = httpServletRequest.getSession();
        //设置用户id到redis当中,过期时间1天
        redisTemplate.opsForValue().set("User:SessionId:" + httpSession.getId(), user.getId(), 1, TimeUnit.DAYS);
        log.info("设置session成功");
        //封装vo层数据
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        log.info("用户登录成功");
        return userVO;
    }
}
