package ai.weixiu.handler;



import ai.weixiu.exceprion.ActivateException;
import ai.weixiu.exceprion.NameOrPasswordException;
import ai.weixiu.exceprion.NullException;
import ai.weixiu.pojo.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


@RestControllerAdvice
@Slf4j
public class WebExceptionHandler {

    @ExceptionHandler(NullException.class)
    public Result handler(NullException e) {
        log.info(e.getMessage());
        return Result.error("200", e.getMessage());
    }

    @ExceptionHandler(NullPointerException.class)
    public Result handler(NullPointerException e) {
        log.info( e.getMessage());
        return Result.error("200", e.getMessage());
    }

    @ExceptionHandler(NameOrPasswordException.class)
    public Result handler(NameOrPasswordException e) {
        log.info(e.getMessage());
        return Result.error("200", e.getMessage());
    }

    @ExceptionHandler(ActivateException.class)
    public Result handler(ActivateException e) {
        log.info(e.getMessage());
        return Result.error("200", e.getMessage());
    }
}
