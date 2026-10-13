package cn.diaojiahong.myyoutube.content.strategy;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.*;
import cn.diaojiahong.myyoutube.video.mapper.DynamicMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DynamicContentStrategy implements ContentTypeStrategy {
    private final DynamicMapper dynamics;
    @Override public ContentTypeEnum type() { return ContentTypeEnum.DYNAMIC; }
    @Override public ContentMedia prepare(MediaDraft draft) {
        return PreparedDrafts.media(draft);
    }

    @Override public void persist(Long id, ContentMedia media) {
        dynamics.insert(new Dynamic().setId(id).setMediaJson(JsonUtil.toJson(media.dynamic())));
    }
    @Override public Map<Long, ContentMedia> loadMedia(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return dynamics.selectBatchIds(ids).stream().collect(Collectors.toMap(Dynamic::getId,
                d -> new ContentMedia("dynamic", null, JsonUtil.fromJson(d.getMediaJson(), DynamicMedia.class))));
    }
}
