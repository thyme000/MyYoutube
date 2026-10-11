package cn.diaojiahong.myyoutube.content.strategy;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.*;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class VideoContentStrategy implements ContentTypeStrategy {
    private final VideoMapper videos;
    private final MediaTaskPort tasks;
    @Override public ContentTypeEnum type() { return ContentTypeEnum.VIDEO; }
    @Override public ContentMedia prepare(MediaDraft draft) {
        var output = tasks.getTaskResults(draft.getId()).stream()
                .filter(t -> "TRANSCODE".equals(t.taskType()) && "SUCCESS".equals(t.status()))
                .findFirst().orElseThrow(() -> new BizException(BizException.BIZ_ERROR, "缺少转码产物"));
        try {
            var json = StructuredJson.object(output.resultJson());
            var media = new ContentMedia("video", new VideoMedia(json.get("processedObjectKey").getAsString(), json.get("durationMs").getAsLong()), null);
            MediaInputs.validate(media, null);
            return media;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BizException(BizException.BIZ_ERROR, "转码产物不合法");
        }
    }
    @Override public void persist(Long id, ContentMedia media) {
        videos.insert(new Video().setId(id).setObjectKey(media.video().objectKey()).setDurationMs(media.video().durationMs()));
    }
    @Override public Map<Long, ContentMedia> loadMedia(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return videos.selectBatchIds(ids).stream().collect(Collectors.toMap(Video::getId,
                v -> new ContentMedia("video", new VideoMedia(v.getObjectKey(), v.getDurationMs()), null)));
    }
}
