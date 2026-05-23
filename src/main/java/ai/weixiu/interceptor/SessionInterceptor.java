package ai.weixiu.interceptor;

import ai.weixiu.common.RedisKey;
import ai.weixiu.pojo.Result;
import ai.weixiu.utils.BaseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class SessionInterceptor implements HandlerInterceptor {
    private final RedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public SessionInterceptor(
            RedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${ai.internal-token:fix-agent-internal-2026}") String internalToken
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
        // 获取请求路径
        String uri = request.getRequestURI();

        // 放行登录和注册接口
        if (uri.contains("/login") || uri.contains("/register")) {
            return true;
        }

        // 内部服务接口：通过共享密钥鉴权（Python 等内部服务携带 X-Internal-Token 请求头）
        if (uri.startsWith("/weixiu/memory/")) {
            String token = request.getHeader("X-Internal-Token");
            if (internalToken.equals(token)) {
                return true;
            }
            log.warn("内部接口鉴权失败，拦截: {}", uri);
            writeJsonResponse(response, Result.error("403", "禁止访问"));
            return false;
        }

        // 从 Redis 中校验登录状态
        HttpSession session = request.getSession();
        String sessionId = session.getId();
        Object userId = redisTemplate.opsForValue().get(RedisKey.USER_SESSION_ID + sessionId);

        if (userId == null) {
            log.info("用户未登录，拦截请求: {}", uri);
            writeJsonResponse(response, Result.error("401", "未登录"));
            return false;
        }
        // 续期 Redis 中的 session 过期时间
        redisTemplate.expire("User:SessionId:" + sessionId, 1, TimeUnit.DAYS);
        //将当前用户的 id保存到 当前线程当中
        BaseContext.setCurrentId(Long.parseLong(userId.toString()));
        log.info("用户已登录，用户ID: {}，请求: {}", userId, uri);
        return true;
    }

    private void writeJsonResponse(HttpServletResponse response, Result result) throws IOException {
        int status = "403".equals(result.getCode()) ? HttpServletResponse.SC_FORBIDDEN : HttpServletResponse.SC_UNAUTHORIZED;
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
