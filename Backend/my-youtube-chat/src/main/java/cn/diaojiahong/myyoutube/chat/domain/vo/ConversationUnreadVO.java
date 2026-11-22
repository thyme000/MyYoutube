package cn.diaojiahong.myyoutube.chat.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 当前用户的聊天未读。totalUnread 是本次清单里未读数之和。
 * 从收件箱移除的私聊水位已推到当时的最新序号，未读为 0；新消息会让它重新出现并计入总数。
 * 当前打开且仍是成员的会话不在清单里时，补一条明细和 lastSeq。
 */
@Data
public class ConversationUnreadVO {
    private Long totalUnread;
    private List<ConversationUnreadItemVO> conversations;
}
