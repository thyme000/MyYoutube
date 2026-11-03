package cn.diaojiahong.myyoutube.gateway.auth;

import cn.diaojiahong.myyoutube.common.chain.ChainStep;
import cn.diaojiahong.myyoutube.common.media.ContentRoutes;
import cn.diaojiahong.myyoutube.security.JwtPayload;
import cn.diaojiahong.myyoutube.security.PlaybackTokens;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@Import(PlaybackTokens.class)
public class PlaybackAuthStep implements ChainStep<AuthContext> {
    private final PlaybackTokens tokens;

    public PlaybackAuthStep(PlaybackTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public String group() { return GatewayAuthChain.GROUP; }

    @Override
    public int order() { return 15; }

    @Override
    public Mono<Void> apply(AuthContext ctx) {
        ContentRoutes.Resource resource = ContentRoutes.match(ctx.getPath());
        boolean stream = resource != null;
        List<String> supplied = ctx.getExchange().getRequest().getQueryParams().get(PlaybackTokens.QUERY_PARAM);
        if (!stream && supplied == null) return Mono.empty();

        HttpMethod method = ctx.getExchange().getRequest().getMethod();
        PlaybackTokens.Grant grant = supplied != null && supplied.size() == 1 ? tokens.verify(supplied.get(0)) : null;
        if (!stream || (method != HttpMethod.GET && method != HttpMethod.HEAD) || grant == null
                || !Long.toString(grant.contentId()).equals(resource.contentId())
                || grant.scope() != resource.scope()) {
            ctx.reject();
        } else {
            ctx.playbackGrant(grant);
            // 接着执行现有 token-ver 检查，改密和封号同样使旧播放凭证失效。
            ctx.jwtPayload(new JwtPayload(grant.userId(), grant.issuedAtMillis()));
        }
        return Mono.empty();
    }
}
