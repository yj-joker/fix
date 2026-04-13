package ai.weixiu.controller;


import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.dto.UserDTO;
import ai.weixiu.pojo.dto.UserLoginDTO;
import ai.weixiu.pojo.query.UserQuery;
import ai.weixiu.pojo.vo.UserVO;
import ai.weixiu.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
    public Result batchRegister(MultipartFile file) {
        userService.batchRegister(file);
        return Result.success();
    }

    /*
     * 用户登录
     * */
    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public Result login(@Valid @RequestBody UserLoginDTO userLoginDTO, HttpServletRequest httpRequest) {
        UserVO userVO = userService.login(userLoginDTO, httpRequest);
        return Result.success(userVO);
    }

    /*
     * 根据用户id查询用户信息
     * */
    @PostMapping("/getUserById")
    @Operation(summary = "根据用户id查询用户信息")
    public Result getUserById(Integer id) {
        UserVO userVO = userService.getUserById(id);
        return Result.success(userVO);
    }

    /*
     * 根据用户id批量删除用户
     * */
    @DeleteMapping("/deleteByIds")
    @Operation(summary = "根据用户id批量删除用户")
    public Result deleteByIds(@RequestBody List<Integer> ids) {
        userService.removeByIds(ids);
        return Result.success();
    }
    /*
    * 根据用户id修改用户信息
    * */
    @PostMapping("/updateUser")
    @Operation(summary = "修改用户信息")
    public Result updateById(@RequestBody UserDTO userDTO) {
        userService.updateUser(userDTO);
        return Result.success();
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
