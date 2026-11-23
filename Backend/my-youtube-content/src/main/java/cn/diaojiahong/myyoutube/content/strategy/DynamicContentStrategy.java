package cn.diaojiahong.myyoutube.content.strategy;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.content.domain.media.ContentMediaResource;
import cn.diaojiahong.myyoutube.content.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.content.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.video.domain.po.*;
import cn.diaojiahong.myyoutube.video.mapper.DynamicMapper;
import cn.diaojiahong.myyoutube.video.service.IAudioService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DynamicContentStrategy implements ContentTypeStrategy {
    private final DynamicMapper dynamics;
    private final IAudioService audios;

    @Override
    public ContentTypeEnum type() {
        return ContentTypeEnum.DYNAMIC;
    }

    @Override
    public ContentMedia prepare(MediaDraft draft) {
        return PreparedDrafts.media(draft);
    }

    @Override
    public void validateSubmission(ContentMedia media, Long authorId) {
        DynamicMedia.Audio audio = media.dynamic().audio();
        if (audio != null) audios.lockReferenceable(audio.id(), authorId);
    }

    @Override
    public Long audioId(ContentMedia media) {
        DynamicMedia.Audio audio = media.dynamic().audio();
        return audio == null ? null : audio.id();
    }

    @Override
    public void persist(Long id, ContentMedia media) {
        dynamics.insert(new Dynamic().setId(id).setMediaJson(JsonUtil.toJson(media.dynamic())));
    }

    @Override
    public Map<Long, ContentMedia> loadMedia(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return dynamics.selectBatchIds(ids).stream().collect(Collectors.toMap(Dynamic::getId,
                d -> new ContentMedia(ContentTypeEnum.DYNAMIC.getCode(), null, JsonUtil.fromJson(d.getMediaJson(), DynamicMedia.class))));
    }

    @Override
    public Long maxResumePositionMs(ContentMedia media) {
        // 续播沿用图片轮播时间轴，不含成片的交叠转场；循环末尾之后回到首张。
        long timelineDurationMs = media.dynamic().items().stream().mapToLong(DynamicMedia.Image::durationMs).sum();
        return timelineDurationMs > 0 ? timelineDurationMs - 1 : null;
    }

    @Override
    public ContentMediaResource loadResource(Long id) {
        ContentMedia media = loadMedia(List.of(id)).get(id);
        return media == null ? null : new ContentMediaResource(media, null, null);
    }
}
