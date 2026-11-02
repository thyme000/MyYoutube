package cn.diaojiahong.myyoutube.gateway.auth;

import cn.diaojiahong.myyoutube.common.chain.ChainStep;
import cn.diaojiahong.myyoutube.security.JwtPayload;
import cn.diaojiahong.myyoutube.security.PlaybackTokens;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Import(PlaybackTokens.class)
public class PlaybackAuthStep implements ChainStep<AuthContext> {
    private static final Pattern RESOURCE = Pattern.compile(
            "^(/contents|/admin/content)/([1-9][0-9]*)/streams/(?:master\\.m3u8|[0-9]+/index\\.m3u8)$");
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
        Matcher resource = RESOURCE.matcher(ctx.getPath());
        boolean stream = resource.matches();
        List<String> supplied = ctx.getExchange().getRequest().getQueryParams().get(PlaybackTokens.QUERY_PARAM);
        if (!stream && supplied == null) return Mono.empty();

        HttpMethod method = ctx.getExchange().getRequest().getMethod();
        PlaybackTokens.Grant grant = supplied != null && supplied.size() == 1 ? tokens.verify(supplied.get(0)) : null;
        if (!stream || (method != HttpMethod.GET && method != HttpMethod.HEAD) || grant == null
                || !Long.toString(grant.contentId()).equals(resource.group(2))
                || grant.scope() != ("/admin/content".equals(resource.group(1))
                        ? PlaybackTokens.Scope.REVIEW : PlaybackTokens.Scope.VIEW)) {
            ctx.reject();
        } else {
            ctx.playbackGrant(grant);
            // 接着执行现有 token-ver 检查，改密和封号同样使旧播放凭证失效。
            ctx.jwtPayload(new JwtPayload(grant.userId(), grant.issuedAtMillis()));
        }
        return Mono.empty();
    }
}
