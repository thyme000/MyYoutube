package cn.diaojiahong.myyoutube.content.strategy;

import cn.diaojiahong.myyoutube.common.media.*;
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
        videos.insert(new Video().setId(id).setObjectKey(media.video().objectKey()).setDurationMs(media.video().durationMs()));
    }

    @Override
    public Map<Long, ContentMedia> loadMedia(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return videos.selectBatchIds(ids).stream().collect(Collectors.toMap(Video::getId,
                v -> new ContentMedia("video", new VideoMedia(v.getObjectKey(), v.getDurationMs()), null)));
    }
}
