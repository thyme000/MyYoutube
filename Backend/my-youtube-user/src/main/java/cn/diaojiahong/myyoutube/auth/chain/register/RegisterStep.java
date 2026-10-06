package cn.diaojiahong.myyoutube.auth.chain.register;

import cn.diaojiahong.myyoutube.auth.domain.dto.RegisterRequestDTO;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStep;

/**
 * 注册校验步骤基类: 收拢分组与排序, 上下文直接用 {@link RegisterRequestDTO},
 * trim 回写等规范化结果也沉淀在 DTO 上供后续步骤与落库使用。
 */
public abstract class RegisterStep implements SyncChainStep<RegisterRequestDTO> {

    private final int order;

    protected RegisterStep(int order) {
        this.order = order;
    }

    @Override
    public final String group() {
        return RegisterChain.GROUP;
    }

    @Override
    public final int order() {
        return order;
    }
}
