package ai.weixiu.enumerate;

public enum GenderEnum {
    MALE(1, "男"),
    FEMALE(2, "女");
    private Integer code;
    private String message;
    GenderEnum(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
