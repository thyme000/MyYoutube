package cn.diaojiahong.myyoutube.chat.domain.enums;

public enum ConversationStatus {
    ACTIVE,
    WAITING_REPLY,
    CLOSED;

    public static ConversationStatus afterFirstPrivateMessage(boolean mutual) {
        return mutual ? ACTIVE : WAITING_REPLY;
    }
}
