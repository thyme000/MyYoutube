package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.security.PlaybackTokens.Scope;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 内容 HTTP 路径契约：控制器映射、播放链接生成和网关识别共用同一套定义。 */
public final class ContentRoutes {
    public static final String CONTENT_BASE = "/contents";
    public static final String DRAFT_BASE = CONTENT_BASE + "/drafts";
    public static final String REVIEW_CONTENT_BASE = "/admin/content";
    private static final String STREAM_PATH = "/streams";

    public static final String VIEW_STREAM_BASE = CONTENT_BASE + "/{contentId}" + STREAM_PATH;
    public static final String REVIEW_STREAM_BASE = REVIEW_CONTENT_BASE + "/{contentId}" + STREAM_PATH;
    public static final String MASTER_PATH = "/" + HlsPlaylist.MASTER_FILENAME;
    public static final String RENDITION_PATH = "/{index}/" + HlsPlaylist.PLAYLIST_FILENAME;

    private static final Pattern RESOURCE = Pattern.compile(
            "^(" + Pattern.quote(CONTENT_BASE) + "|" + Pattern.quote(REVIEW_CONTENT_BASE) + ")/([1-9][0-9]*)"
                    + Pattern.quote(STREAM_PATH) + "/(?:" + Pattern.quote(HlsPlaylist.MASTER_FILENAME)
                    + "|[0-9]+/" + Pattern.quote(HlsPlaylist.PLAYLIST_FILENAME) + ")$");

    private ContentRoutes() {}

    public static String master(Scope scope, long contentId) {
        return base(scope, contentId) + HlsPlaylist.MASTER_FILENAME;
    }

    public static String rendition(Scope scope, long contentId, int index) {
        return base(scope, contentId) + relativeRendition(index);
    }

    /** 主清单中的档位地址相对于当前 streams 目录。 */
    public static String relativeRendition(int index) {
        return index + "/" + HlsPlaylist.PLAYLIST_FILENAME;
    }

    public static Resource match(String path) {
        Matcher resource = RESOURCE.matcher(path);
        if (!resource.matches()) return null;
        Scope scope = REVIEW_CONTENT_BASE.equals(resource.group(1)) ? Scope.REVIEW : Scope.VIEW;
        return new Resource(resource.group(2), scope);
    }

    private static String base(Scope scope, long contentId) {
        String prefix = switch (scope) {
            case VIEW -> CONTENT_BASE;
            case REVIEW -> REVIEW_CONTENT_BASE;
        };
        return prefix + "/" + contentId + STREAM_PATH + "/";
    }

    public record Resource(String contentId, Scope scope) {}
}
