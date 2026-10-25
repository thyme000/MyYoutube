package cn.diaojiahong.myyoutube.content.strategy;

import cn.diaojiahong.myyoutube.common.media.ContentMedia;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import java.util.Collection;
import java.util.Map;

public interface ContentTypeStrategy {
    ContentTypeEnum type();
    ContentMedia prepare(MediaDraft draft);
    default void validateSubmission(ContentMedia media, Long authorId) {}
    void persist(Long contentId, ContentMedia media);
    Map<Long, ContentMedia> loadMedia(Collection<Long> ids);
}
