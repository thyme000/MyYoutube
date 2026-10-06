package cn.diaojiahong.myyoutube.video.chain.dynamic;

import cn.diaojiahong.myyoutube.common.chain.SyncChainStep;

/**
 * 动态发布步骤基类: 收拢分组与排序, 失败一律抛 BizException 快速终止。
 */
public abstract class DynamicPublishStep implements SyncChainStep<DynamicPublishContext> {

    private final int order;

    protected DynamicPublishStep(int order) {
        this.order = order;
    }

    @Override
    public final String group() {
        return DynamicPublishChain.GROUP;
    }

    @Override
    public final int order() {
        return order;
    }
}
