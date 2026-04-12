package ai.weixiu.controller;


import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.dto.UserDTO;
import ai.weixiu.pojo.query.UserQuery;
import ai.weixiu.pojo.vo.UserVO;
import ai.weixiu.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * <p>
 * 用户表 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-04-08
 */
@RestController
@RequestMapping("/weixiu/user")
@AllArgsConstructor
@Tag(name = "用户管理")
public class UserController {
    private final UserService userService;

    /*
     * 用户注册
     * */
    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public Result register(MultipartFile file) {
        userService.register(file);
        return Result.success();
    }

    /*
     * 用户登录
     * */
    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public Result login(@Valid @RequestBody UserDTO userDTO, HttpServletRequest httpRequest) {
        UserVO userVO = userService.login(userDTO, httpRequest);
        return Result.success(userVO);
    }
    /*
     * 分页查询所有用户
     * */
    @PostMapping("/list")
    @Operation(summary = "分页查询所有用户")
    public Result list(@RequestBody UserQuery userQuery) {
        List<UserVO> userVOList = userService.getUserList(userQuery);
        return Result.success(userVOList);
    }
}
