package cn.diaojiahong.myyoutube.content.domain.media;

import cn.diaojiahong.myyoutube.common.media.ContentMedia;
import cn.diaojiahong.myyoutube.common.media.VideoPlayback;

/** 未签名的内容展示快照，供类型策略、缓存和响应组装共同使用。 */
public record ContentMediaResource(ContentMedia media, VideoPlayback playback, String playbackVersion) {
    public ContentMediaResource {
        if (media.video() != null && (playback == null || playbackVersion == null || playbackVersion.isBlank()))
            throw new IllegalArgumentException("视频展示资源缺少播放描述或版本");
    }
}
