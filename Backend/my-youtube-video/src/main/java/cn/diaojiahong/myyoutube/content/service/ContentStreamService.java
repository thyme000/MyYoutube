package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.media.HlsPlaylist;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.VideoPlayback;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackQuality;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.security.PlaybackTokens;
import cn.diaojiahong.myyoutube.security.PlaybackTokens.Scope;
import cn.diaojiahong.myyoutube.video.api.dto.VideoPlaybackVO;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import software.amazon.awssdk.http.SdkHttpMethod;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ContentStreamService {
    private static final int SIGN_SECONDS = 1200;
    private final ContentAccessService access;
    private final VideoMapper videos;
    private final S3StorageUtil storage;
    private final PlaybackTokens tokens;

    public VideoPlaybackVO playback(Long contentId, Long viewer) {
        return playback(contentId, viewer, Scope.VIEW);
    }

    /** 仅供已完成管理员权限检查的内部审核接口签发。 */
    public VideoPlaybackVO reviewPlayback(Long contentId, Long viewer) {
        return playback(contentId, viewer, Scope.REVIEW);
    }

    private VideoPlaybackVO playback(Long contentId, Long viewer, Scope scope) {
        Video video = readableVideo(contentId, viewer, scope);
        VideoPlayback playback = descriptor(video);
        PlaybackTokens.Grant grant = tokens.issue(viewer, contentId, video.getDurationMs(), scope);
        String base = (scope == Scope.REVIEW ? "/admin/content/" : "/contents/") + contentId + "/streams/";
        List<VideoPlaybackVO.Rendition> renditions = playback.renditions().stream().map(rendition ->
                new VideoPlaybackVO.Rendition(rendition.index(), rendition.width(), rendition.height(),
                        withToken(base + rendition.index() + "/" + HlsPlaylist.PLAYLIST_FILENAME, grant.token()))).toList();
        // 前端分片缓存键与授权续期检查使用此版本；摘要不用于鉴权。
        String version = DigestUtils.md5DigestAsHex(video.getPlaybackJson().getBytes(StandardCharsets.UTF_8));
        return new VideoPlaybackVO(playback.width(), playback.height(), version, grant.expiresAt(),
                withToken(base + HlsPlaylist.MASTER_FILENAME, grant.token()), renditions);
    }

    public String masterPlaylist(Long contentId, Long viewer, String token) {
        return masterPlaylist(contentId, viewer, token, Scope.VIEW);
    }

    public String reviewMasterPlaylist(Long contentId, Long viewer, String token) {
        requireGrant(contentId, viewer, token, Scope.REVIEW);
        return masterPlaylist(contentId, viewer, token, Scope.REVIEW);
    }

    private String masterPlaylist(Long contentId, Long viewer, String token, Scope scope) {
        Video video = readableVideo(contentId, viewer, scope);
        VideoPlayback playback = descriptor(video);
        return HlsPlaylist.master(playback.renditions(),
                rendition -> withToken(rendition.index() + "/" + HlsPlaylist.PLAYLIST_FILENAME, token));
    }

    public String playlist(Long contentId, int index, Long viewer, String token) {
        return playlist(contentId, index, viewer, token, Scope.VIEW);
    }

    public String reviewPlaylist(Long contentId, int index, Long viewer, String token) {
        return playlist(contentId, index, viewer, token, Scope.REVIEW);
    }

    private String playlist(Long contentId, int index, Long viewer, String token, Scope scope) {
        PlaybackTokens.Grant grant = requireGrant(contentId, viewer, token, scope);
        VideoPlayback.Rendition rendition = readableRendition(contentId, index, viewer, scope);
        String text = new String(storage.readObject(rendition.playlistObjectKey()), StandardCharsets.UTF_8);
        String mediaKey = rendition.mediaObjectKey();
        String filename = mediaKey.substring(mediaKey.lastIndexOf('/') + 1);

        long seconds = Math.min(SIGN_SECONDS, (grant.expiresAt() - System.currentTimeMillis()) / 1000);
        if (seconds <= 0) throw new BizException(ErrorCode.UNAUTHORIZED);

        // 同一档位的初始化段和全部字节范围共用一个签名，不再逐片经过应用服务。
        String mediaUrl = storage.presignReadUrl(mediaKey, seconds, SdkHttpMethod.GET);
        return text.replace(filename, mediaUrl);
    }

    private VideoPlayback descriptor(Video video) {
        if (video.getPlaybackJson() == null || video.getPlaybackJson().isBlank())
            throw new BizException(ErrorCode.STATE_CONFLICT, "HLS 播放资源尚未准备完成");
        return JsonUtil.fromJson(video.getPlaybackJson(), VideoPlayback.class);
    }

    private PlaybackTokens.Grant requireGrant(Long contentId, Long viewer, String token, Scope scope) {
        PlaybackTokens.Grant grant = tokens.verify(token);
        if (grant == null || grant.scope() != scope || grant.contentId() != contentId || grant.userId() != viewer)
            throw new BizException(ErrorCode.UNAUTHORIZED);
        return grant;
    }

    private static String withToken(String url, String token) {
        return url + "?" + PlaybackTokens.QUERY_PARAM + "=" + token;
    }

    private VideoPlayback.Rendition readableRendition(Long contentId, int index, Long viewer, Scope scope) {
        Video video = readableVideo(contentId, viewer, scope);
        VideoPlayback playback = descriptor(video);
        PlaybackQuality quality = PlaybackQuality.fromCode(index);
        if (quality == null) throw new BizException(ErrorCode.NOT_FOUND, "播放档位不存在");
        return playback.renditions().stream().filter(item -> item.index() == quality.code())
                .findFirst().orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "播放档位不存在"));
    }

    private Video readableVideo(Long contentId, Long viewer, Scope scope) {
        Content content = scope == Scope.REVIEW ? access.forReview(contentId) : access.readable(contentId, viewer);
        if (content.getType() != ContentTypeEnum.VIDEO)
            throw new BizException(ErrorCode.NOT_FOUND, "内容不是视频");
        Video video = videos.selectById(contentId);
        if (video == null) throw new BizException(ErrorCode.NOT_FOUND, "视频不存在");
        return video;
    }
}
