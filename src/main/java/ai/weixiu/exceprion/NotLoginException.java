package ai.weixiu.exceprion;

public class NotLoginException extends RuntimeException {
    public NotLoginException(String message) {
        super(message);
    }
}
