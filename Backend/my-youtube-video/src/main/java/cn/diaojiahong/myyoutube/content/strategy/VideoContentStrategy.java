package cn.diaojiahong.myyoutube.content.strategy;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.content.domain.media.ContentMediaResource;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.*;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class VideoContentStrategy implements ContentTypeStrategy {
    private static final double FINISH_RATIO = 0.95;

    private final VideoMapper videos;

    @Override
    public ContentTypeEnum type() {
        return ContentTypeEnum.VIDEO;
    }

    @Override
    public ContentMedia prepare(MediaDraft draft) {
        return PreparedDrafts.media(draft);
    }

    @Override
    public void persist(Long id, ContentMedia media) {
        VideoPlayback playback = VideoPlayback.from(media.preparation());
        videos.insert(new Video().setId(id).setObjectKey(media.video().objectKey()).setDurationMs(media.video().durationMs())
                .setPlaybackJson(JsonUtil.toJson(playback)));
    }

    @Override
    public Map<Long, ContentMedia> loadMedia(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return videos.selectBatchIds(ids).stream().collect(Collectors.toMap(Video::getId, this::media));
    }

    @Override
    public Long maxResumePositionMs(ContentMedia media) {
        long durationMs = media.video().durationMs();
        return durationMs > 0 ? durationMs : null;
    }

    @Override
    public boolean isFinished(ContentMedia media, long effectiveMs, long positionMs) {
        long durationMs = media.video().durationMs();
        double finishThresholdMs = durationMs * FINISH_RATIO;
        return durationMs > 0 && effectiveMs >= finishThresholdMs && positionMs >= finishThresholdMs;
    }

    @Override
    public ContentMediaResource loadResource(Long id) {
        Video video = videos.selectById(id);
        if (video == null) return null;

        String json = video.getPlaybackJson();
        if (json == null || json.isBlank())
            throw new BizException(ErrorCode.STATE_CONFLICT, "HLS 播放资源尚未准备完成");

        VideoPlayback playback = JsonUtil.fromJson(json, VideoPlayback.class);
        String version = DigestUtils.md5DigestAsHex(json.getBytes(StandardCharsets.UTF_8));
        return new ContentMediaResource(media(video), playback, version);
    }

    private ContentMedia media(Video video) {
        return new ContentMedia(ContentTypeEnum.VIDEO.getCode(), new VideoMedia(video.getObjectKey(), video.getDurationMs()), null);
    }
}
