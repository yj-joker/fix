package ai.weixiu.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>
 * 用户表
 * </p>
 *
 * @author author
 * @since 2026-04-08
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 身份证号，登录账号
     */
    private String username;

    /**
     * 姓名
     */
    private String name;

    /**
     * 工号
     */
    private String number;

    /**
     * bcrypt加密密码
     */
    private String password;

    /**
     * 0=男, 1=女
     */
    private Integer gender;

    /**
     * 0=员工, 1=管理员
     */
    private Integer type;

    /**
     * 手机号
     */
    private String phone;

    /**
     * 入职日期
     */
    private LocalDate hireDate;

    /**
     * 0=未激活, 1=已激活
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 最后登录时间
     */
    private LocalDateTime lastLoginTime;


}
