package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.common.chain.SyncChainStep;

/**
 * 发消息守卫步骤基类: 收拢分组与排序, 失败一律抛 BizException 快速终止。
 */
public abstract class MessageSendStep implements SyncChainStep<MessageSendContext> {

    private final int order;

    protected MessageSendStep(int order) {
        this.order = order;
    }

    @Override
    public final String group() {
        return MessageSendChain.GROUP;
    }

    @Override
    public final int order() {
        return order;
    }
}
