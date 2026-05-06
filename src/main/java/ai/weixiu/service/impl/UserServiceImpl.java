package ai.weixiu.service.impl;

import ai.weixiu.common.RedisKey;
import ai.weixiu.enumerate.EmailEnum;
import ai.weixiu.enumerate.StatusEnum;
import ai.weixiu.exceprion.EmailException;
import ai.weixiu.exceprion.NameOrPasswordException;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.exceprion.NullException;
import ai.weixiu.pojo.dto.UserDTO;
import ai.weixiu.pojo.dto.UserLoginDTO;
import ai.weixiu.entity.User;
import ai.weixiu.mapper.UserMapper;
import ai.weixiu.pojo.query.UserQuery;
import ai.weixiu.pojo.vo.UserVO;
import ai.weixiu.service.UserService;
import ai.weixiu.utils.BaseContext;
import ai.weixiu.utils.ExcelUtils;
import ai.weixiu.utils.IsNullUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final RedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender javaMailSender;
    private String MyEmail;

    @Autowired
    public UserServiceImpl(RedisTemplate redisTemplate, PasswordEncoder passwordEncoder, JavaMailSender javaMailSender) {
        this.redisTemplate = redisTemplate;
        this.passwordEncoder = passwordEncoder;
        this.javaMailSender = javaMailSender;
    }

    @Value("${spring.mail.username}")
    public void setMyEmail(String MyEmail) {
        this.MyEmail = MyEmail;
    }

    /*
     * 批量添加 用户
     * */
    @Override
    @Transactional
    public int batchRegister(MultipartFile file) {
        //判断文件是否是excel文件
        if (!ExcelUtils.isExcelFile(file)) {
            throw new NullException("必须上传excel文件");
        }
        //读取excel获取数据
        List<User> users = ExcelUtils.readExcel(file, User.class);
        log.info("共读取到 {} 条数据，开始处理", users.size());

        // 创建线程池，线程数根据CPU核心数设置
        int threadCount = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        //设置每次处理的记录数
        int batchSize = 500;
        List<CompletableFuture<List<User>>> futures = new ArrayList<>();

        // 多线程并行处理密码加密
        for (int i = 0; i < users.size(); i += batchSize) {
            int end = Math.min(i + batchSize, users.size());
            List<User> batch = users.subList(i, end);

            CompletableFuture<List<User>> future = CompletableFuture.supplyAsync(() -> {
                LocalDateTime now = LocalDateTime.now();
                for (User user : batch) {
                    user.setPassword(passwordEncoder.encode("123456"));
                    user.setCreateTime(now);
                    user.setUpdateTime(now);
                }
                return batch;
            }, executor);
            futures.add(future);
        }

        // 等待所有加密任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 收集加密后的数据并保存
        List<User> allProcessedUsers = new ArrayList<>();
        for (CompletableFuture<List<User>> future : futures) {
            allProcessedUsers.addAll(future.join());
        }

        // 分批保存到数据库
        int totalSaved = 0;
        for (int i = 0; i < allProcessedUsers.size(); i += batchSize) {
            int end = Math.min(i + batchSize, allProcessedUsers.size());
            List<User> batch = allProcessedUsers.subList(i, end);
            this.saveBatch(batch);
            totalSaved += batch.size();
            log.info("已保存第 {} 批数据，累计 {} 条", (i / batchSize + 1), totalSaved);
        }

        // 关闭线程池
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("全部数据保存完成，共 {} 条", totalSaved);
        return totalSaved;
    }

    /*
     * 用户登录
     * */
    @Override
    public UserVO login(UserLoginDTO userLoginDTO, HttpServletRequest httpServletRequest) {
        // 查询用户
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, userLoginDTO.getUsername());
        User user = this.getOne(queryWrapper);
        // 用户不存在
        if (user == null) {
            log.info("用户不存在");
            throw new NameOrPasswordException("用户名或密码错误");
        } else {
            //进行bcrypt密码校验
            if (!passwordEncoder.matches(userLoginDTO.getPassword(), user.getPassword())) {
                log.info("密码错误");
                throw new NameOrPasswordException("用户名或密码错误");
            }
        }
//        if (Objects.equals(user.getStatus(), StatusEnum.DEACTIVATED.getCode())) {
//            throw new ArithmeticException("用户未激活,请先激活");
//        }
        // 登录成功,设置最后登录时间
        user.setLastLoginTime(LocalDateTime.now());
        this.updateById(user);
        HttpSession httpSession = httpServletRequest.getSession();
        //设置用户id到redis当中,过期时间1天
        redisTemplate.opsForValue().set(RedisKey.USER_SESSION_ID + httpSession.getId(), user.getId(), 1, TimeUnit.DAYS);
        log.info("设置session成功");
        //封装vo层数据
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        log.info("用户登录成功");
        return userVO;
    }

    /*
     * 用户分页查询
     * */
    @Override
    public List<UserVO> getUserList(UserQuery userQuery) {
        Page<User> page = new Page<>(userQuery.getPage(), userQuery.getSize());
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();

        // 链式拼接查询条件，null 和 "" 自动跳过
        wrapper.like(StringUtils.hasText(userQuery.getName()), User::getName, userQuery.getName())
                .eq(StringUtils.hasText(userQuery.getNumber()), User::getNumber, userQuery.getNumber())
                .eq(userQuery.getGender() != null, User::getGender, userQuery.getGender())
                .like(StringUtils.hasText(userQuery.getPhone()), User::getPhone, userQuery.getPhone())
                .ge(userQuery.getHireDate() != null, User::getHireDate, userQuery.getHireDate());

        // 排序
        if (userQuery.getSortBy() != null) {
            wrapper.orderBy(true,
                    userQuery.getIsAsc() == 1,
                    User::getCreateTime); // 替换为实际的排序字段
        }

        Page<User> result = this.page(page, wrapper);
        return result.getRecords().stream().map(user -> {
            UserVO vo = new UserVO();
            BeanUtils.copyProperties(user, vo);
            return vo;
        }).toList();
    }

    /*
     * 根据用户id查询对应用户
     * */
    @Override
    public UserVO getUserById(Integer id) {
        UserVO userVO = new UserVO();
        User user = this.getById(id);
        if (IsNullUtils.isNull(user)) {
            throw new NotFoundException("用户不存在");
        }
        BeanUtils.copyProperties(user, userVO);
        log.info("查询用户成功");
        return userVO;
    }

    /*
     * 修改用户信息
     * */
    @Override
    public void updateUser(UserDTO userDTO) {
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getId, userDTO.getId())
                .set(User::getName, userDTO.getName())
                .set(User::getNumber, userDTO.getNumber())
                .set(User::getPhone, userDTO.getPhone())
                .set(User::getUpdateTime, LocalDateTime.now());
        this.update(wrapper);
    }

    /*
     * 发送验证码
     * */
    @Override
    public void sendEmail(String email, Integer mode) {
        //先判断邮箱格式是否符合规则
        if (!email.matches("^[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$")) {
            throw new EmailException("邮箱格式不正确");
        }
        //判断用户是否反复发送
        if (redisTemplate.hasKey(RedisKey.USER_EMAIL_CODE + BaseContext.getCurrentId())) {
            throw new EmailException("请勿重复发送验证码");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        //从配置文件中获取当前设置的邮箱
        message.setFrom(MyEmail);
        //设置接收者邮箱
        message.setTo(email);
        //根据不同mode设置不同邮件标题
        if (Objects.equals(mode, EmailEnum.ACTIVATION_EMAIL.getCode())) {
         message.setSubject("维修平台绑定邮箱");
        }
        //设置邮件内容
        String code=getCode();
        message.setText("此次验证码:"+code);
        //将验证码存入redis中，并设置过期时间
        redisTemplate.opsForValue().set(RedisKey.USER_EMAIL_CODE +BaseContext.getCurrentId(), code, 1, TimeUnit.MINUTES);
        javaMailSender.send(message);
    }

    private String getCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            code.append(chars.charAt((int) (Math.random() * chars.length())));
        }
        return code.toString();
    }

}
