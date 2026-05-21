package ai.weixiu.common;

public class RedisKey {
        public static final String USER_SESSION_ID="User:SessionId:";
        public static final String USER_EMAIL_CODE="User:Email:Code:";
        public static final String PREFERENCE_CACHE = "Memory:Preference:";
        public static final String CONSOLIDATION_LOCK = "Memory:Consolidation:Lock:";
        public static final String MANUAL_DETAIL = "Maintenance:Manual:Detail:";
        public static final String MANUAL_DETAIL_LOCK = "Maintenance:Manual:Detail:Lock:";
        public static final String MANUAL_READ_SESSION = "Maintenance:Manual:Read:Session:";
        public static final String MANUAL_READ_SESSION_LOCK = "Maintenance:Manual:Read:Session:Lock:";
        public static final String MANUAL_READ_DURATION = "Maintenance:Manual:Read:Duration:";
        public static final String MANUAL_READ_COUNTED = "Maintenance:Manual:Read:Counted:";
        public static final String MANUAL_RANK_DAY = "Maintenance:Manual:Rank:Day:";
        public static final String MANUAL_RANK_WEEK = "Maintenance:Manual:Rank:Week:";
        public static final String MANUAL_RANK_MONTH = "Maintenance:Manual:Rank:Month:";
        public static final String MANUAL_RANK_TOTAL = "Maintenance:Manual:Rank:Total";
}
