package ai.weixiu.enumerate;

import java.util.Locale;

public enum MaintenanceManualRankType {
    DAY,
    WEEK,
    MONTH,
    TOTAL;

    public static MaintenanceManualRankType parse(String value) {
        if (value == null || value.isBlank()) {
            return DAY;
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported maintenance manual rank type: " + value);
        }
    }
}
