package cn.diaojiahong.myyoutube.chat.chain.message;

/**
 * 发消息守卫链在 {@link cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry} 里的分组标识。
 */
public final class MessageSendChain {

    public static final String GROUP = "chat:message-send";

    private MessageSendChain() {
    }
}
