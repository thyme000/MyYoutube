package cn.diaojiahong.myyoutube.gateway.auth;

import cn.diaojiahong.myyoutube.security.chain.ChainStep;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 白名单步: 精确匹配免验签路径, 命中即原样放行(不透传身份头)。
 * 名单配置驱动, 单点见 gateway application.yml 的 my-youtube.auth.whitelist。
 */
@Component
public class WhitelistAuthStep implements ChainStep<AuthContext> {

    private final List<String> whitelist;

    public WhitelistAuthStep(@Value("${my-youtube.auth.whitelist}") List<String> whitelist) {
        this.whitelist = whitelist;
    }

    @Override
    public String group() {
        return GatewayAuthChain.GROUP;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Mono<Void> apply(AuthContext ctx) {
        if (whitelist.stream().anyMatch(ctx.getPath()::equals)) {
            ctx.skip();
        }
        return Mono.empty();
    }
}
