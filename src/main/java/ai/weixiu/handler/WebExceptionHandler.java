package ai.weixiu.handler;



import ai.weixiu.exceprion.NameOrPasswordException;
import ai.weixiu.pojo.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


@RestControllerAdvice
@Slf4j
public class WebExceptionHandler {

    @ExceptionHandler(NullPointerException.class)
    public Result handler(NullPointerException e) {
        log.error("传递参数错误", e);
        return Result.error("200", "传递参数错误");
    }

@ExceptionHandler(NameOrPasswordException.class)
    public Result handler(NameOrPasswordException e) {
        log.error("用户名或密码错误", e);
        return Result.error("200", "用户名或密码错误");
    }
}
