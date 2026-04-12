package ai.weixiu.service.impl;

import ai.weixiu.enumerate.StatusEnum;
import ai.weixiu.exceprion.NameOrPasswordException;
import ai.weixiu.pojo.dto.UserDTO;
import ai.weixiu.entity.User;
import ai.weixiu.mapper.UserMapper;
import ai.weixiu.pojo.query.UserQuery;
import ai.weixiu.pojo.vo.UserVO;
import ai.weixiu.service.UserService;
import ai.weixiu.utils.ExcelUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
@AllArgsConstructor
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final RedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;


    @Override
    @Transactional
    public int register(MultipartFile file) {
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
            int start = i;
            int end = Math.min(i + batchSize, users.size());
            List<User> batch = users.subList(start, end);

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

    @Override
    public UserVO login(UserDTO userDTO, HttpServletRequest httpServletRequest) {
        // 查询用户
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, userDTO.getUsername());
        User user = this.getOne(queryWrapper);
        // 用户不存在
        if (user == null) {
            log.info("用户不存在");
            throw new NameOrPasswordException("用户名或密码错误");
        } else {
            //进行bcrypt密码校验
            if (!passwordEncoder.matches(userDTO.getPassword(), user.getPassword())) {
                log.info("密码错误");
                throw new NameOrPasswordException("用户名或密码错误");
            }
        }
        if(user.getStatus() == StatusEnum.DEACTIVATED.getCode()){
            throw new ArithmeticException("用户未激活,请先激活");
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
        List<UserVO> userVOList = result.getRecords().stream().map(user -> {
            UserVO vo = new UserVO();
            BeanUtils.copyProperties(user, vo);
            return vo;
        }).toList();
        return  userVOList;
    }
}
