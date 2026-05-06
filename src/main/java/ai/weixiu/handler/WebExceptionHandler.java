package ai.weixiu.handler;



import ai.weixiu.exceprion.*;
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
        return Result.error("400", e.getMessage());
    }
    @ExceptionHandler(NameOrPasswordException.class)
    public Result handler(NameOrPasswordException e) {
        log.info(e.getMessage());
        return Result.error("401", e.getMessage());
    }

    @ExceptionHandler(ActivateException.class)
    public Result handler(ActivateException e) {
        log.info(e.getMessage());
        return Result.error("401", e.getMessage());
    }
    @ExceptionHandler(NotFoundException.class)
    public Result handler(NotFoundException e) {
        log.info(e.getMessage());
        return Result.error("404", e.getMessage());
    }
    @ExceptionHandler(EmailException.class)
    public Result handler(EmailException e) {
        log.info(e.getMessage());
        return Result.error("400", e.getMessage());
    }
}
