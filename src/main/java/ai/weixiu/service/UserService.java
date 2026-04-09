package ai.weixiu.service;

import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.dto.UserDTO;
import ai.weixiu.pojo.entity.User;
import ai.weixiu.pojo.vo.UserVO;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.HttpRequestHandler;

/**
 * <p>
 * 用户表 服务类
 * </p>
 *
 * @author author
 * @since 2026-04-08
 */
public interface UserService extends IService<User> {


    UserVO login(UserDTO userDTO, HttpServletRequest httpServletRequest);
}
