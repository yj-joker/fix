package ai.weixiu.aop;

import ai.weixiu.exceprion.NullException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 自动校验controller层所有方法的参数是否合法。
 * 在所有controller方法执行前，对传入的参数进行非空校验。
 * 异常处理：
 * 当参数校验失败时，抛出{@link NullException}异常，
 */
@Aspect
@Component
@Slf4j
public class ParamValidateAspect {

    /**
     * Controller层切入点
     *   匹配规则说明：
     *   第一个 * - 匹配任意返回类型
     *   ai.weixiu.controller.. - 匹配controller包及其子包下的所有类
     *   *.* - 匹配所有类及其所有方法
     *   (..) - 匹配任意参数
     */
    @Pointcut("execution(* ai.weixiu.controller..*.*(..))")
    public void controllerPointcut() {
    }

    /**
     * 参数校验方法
     *
     * @param joinPoint 连接点对象，包含被调用方法的详细信息
     *                  getArgs() - 获取方法传入的参数数组
     * @throws NullException 当参数为null或空字符串时抛出
     */
    @Before("controllerPointcut()")
    public void validateParams(JoinPoint joinPoint) {
        // 获取方法的所有参数
        Object[] args = joinPoint.getArgs();

        // 如果没有参数，直接返回，不进行校验
        if (args == null || args.length == 0) {
            return;
        }

        // 遍历所有参数进行校验
        Arrays.stream(args).forEach(arg -> {
            // 规则1：参数不能为null
            if (arg == null) {
                log.warn("参数校验失败：参数为null");
                throw new NullException("参数不能为空");
            }
            // 规则2：String类型参数不能为空字符串
            if (arg instanceof String && ((String) arg).isEmpty()) {
                log.warn("参数校验失败：参数为空字符串");
                throw new NullException("参数不能为空字符串");
            }
        });
    }
}
