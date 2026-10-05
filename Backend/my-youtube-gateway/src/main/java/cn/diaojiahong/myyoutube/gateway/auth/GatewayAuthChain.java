package cn.diaojiahong.myyoutube.gateway.auth;

/**
 * 网关鉴权链在 {@link cn.diaojiahong.myyoutube.security.chain.ChainStepRegistry} 里的分组标识。
 */
public final class GatewayAuthChain {

    public static final String GROUP = "gateway:auth";

    private GatewayAuthChain() {
    }
}
