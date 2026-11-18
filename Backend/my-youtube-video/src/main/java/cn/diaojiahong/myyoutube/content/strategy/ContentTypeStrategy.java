package cn.diaojiahong.myyoutube.content.strategy;

import cn.diaojiahong.myyoutube.common.media.ContentMedia;
import cn.diaojiahong.myyoutube.content.domain.media.ContentMediaResource;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import java.util.Collection;
import java.util.Map;

public interface ContentTypeStrategy {
    ContentTypeEnum type();

    ContentMedia prepare(MediaDraft draft);

    default void validateSubmission(ContentMedia media, Long authorId) {
    }

    default Long audioId(ContentMedia media) {
        return null;
    }

    void persist(Long contentId, ContentMedia media);

    /** 列表所需的基础媒体，按内容 ID 批量读取。 */
    Map<Long, ContentMedia> loadMedia(Collection<Long> ids);

    /** 最后一个可保存的续播位置，媒体无法续播时返回 null。 */
    Long maxResumePositionMs(ContentMedia media);

    default boolean isFinished(ContentMedia media, long effectiveMs, long positionMs) {
        return false;
    }

    /** 详情和播放所需的未签名展示快照，包含类型专属资源信息。 */
    ContentMediaResource loadResource(Long id);
}
