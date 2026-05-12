package ai.weixiu.pojo.vo;

import lombok.Data;

@Data
public class MemoryUnresolvedVO {
    private String content; //未完成任务摘要描述
    private String type; //未答复回答|进行中任务|用户代办
    private String status;// 是否被用户放弃(比如用户说XXX不需要完成了), active(没有)|superseded (放弃)
}
