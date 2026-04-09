package ai.weixiu.exceprion;

public class NameOrPasswordException extends RuntimeException{
    public NameOrPasswordException(String message) {
        super(message);
    }
}
