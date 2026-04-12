package ai.weixiu.enumerate;


public enum StatusEnum {
    ACTIVATED(1, "已激活"),
    DEACTIVATED(0, "未激活");
    private Integer code;
    private String message;
    StatusEnum(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
    public Integer getCode() {
        return code;
    }
    public String getMessage() {
        return message;
    }

}
