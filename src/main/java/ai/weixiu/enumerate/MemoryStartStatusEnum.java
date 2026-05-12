package ai.weixiu.enumerate;

import lombok.Getter;

@Getter
public enum MemoryStartStatusEnum {
        ACTIVE("active"),
        SUPERSET("superset");

    private final String value;
    MemoryStartStatusEnum(String value) {
        this.value = value;
    }
}
