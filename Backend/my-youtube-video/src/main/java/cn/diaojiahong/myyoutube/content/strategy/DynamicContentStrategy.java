package cn.diaojiahong.myyoutube.content.strategy;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
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
                d -> new ContentMedia("dynamic", null, JsonUtil.fromJson(d.getMediaJson(), DynamicMedia.class))));
    }
}
