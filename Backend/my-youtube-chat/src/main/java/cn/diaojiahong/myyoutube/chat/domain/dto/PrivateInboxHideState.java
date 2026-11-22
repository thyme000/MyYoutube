package cn.diaojiahong.myyoutube.chat.domain.dto;

import lombok.Data;

/** 隐藏私聊前一次普通查询读到的会话序号和成员状态。 */
@Data
public class PrivateInboxHideState {
    private Long lastSeq;
    private String memberId;
    private String lastMessageId;
    private Boolean inboxVisible;
    private Boolean deleted;
}
