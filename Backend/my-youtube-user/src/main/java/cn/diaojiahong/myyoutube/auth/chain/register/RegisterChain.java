package cn.diaojiahong.myyoutube.auth.chain.register;

/**
 * 注册校验链在 {@link cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry} 里的分组标识。
 */
public final class RegisterChain {

    public static final String GROUP = "auth:register";

    private RegisterChain() {
    }
}
