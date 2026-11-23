package cn.diaojiahong.myyoutube.content.api.dto;

import java.util.List;

/** expiresAt 为播放凭证的 Unix 毫秒到期时间；URL 已携带凭证，客户端无需另行拼接。 */
public record VideoPlaybackVO(int width, int height, String playbackVersion, long expiresAt,
                              String adaptivePlaylistUrl, List<Rendition> renditions) {
    public record Rendition(int index, int width, int height, String playlistUrl) {}
}
