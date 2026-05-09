package ai.weixiu.controller;


import ai.weixiu.pojo.Result;
import ai.weixiu.pojo.dto.UserDTO;
import ai.weixiu.pojo.dto.UserLoginDTO;
import ai.weixiu.pojo.query.UserQuery;
import ai.weixiu.pojo.vo.UserVO;
import ai.weixiu.service.UserService;
import ai.weixiu.utils.UpLoadUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
    private final UpLoadUtils upLoadUtils;

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
    @PutMapping("/updateUser")
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
    public Result<List<UserVO>> list(@RequestBody UserQuery userQuery) {
        List<UserVO> userVOList = userService.getUserList(userQuery);
        return Result.success(userVOList);
    }
    /*
    * 向邮箱发送验证码
    * */
    @PostMapping("/sendEmail")
    @Operation(summary = "向邮箱发送验证码")
    public Result sendEmail(String email,Integer mode) {
        userService.sendEmail(email, mode);
        return Result.success();
    }
    /*
    * 验证验证码,并修改密码或绑定邮箱
    * */
    @PostMapping("/verifyEmail")
    @Operation(summary = "验证验证码")
    public Result verifyEmail(String code,Integer mode,String  emailOrPassword) {
        userService.verifyEmail(code, mode,emailOrPassword);
        return Result.success();
    }
    /*
    * 上传图片
    * */
    @PostMapping("/upload")
    @Operation(summary = "上传图片")
    public Result upload(MultipartFile file) {
        String url;
        try {
             url = upLoadUtils.upload(file);
        } catch (IOException ignored) {
            return Result.error("500","上传图片失败,请稍后再试");
        }
        return Result.success(url);
    }
}
