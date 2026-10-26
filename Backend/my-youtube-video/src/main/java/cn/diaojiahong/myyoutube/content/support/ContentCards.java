package cn.diaojiahong.myyoutube.content.support;

import cn.diaojiahong.myyoutube.common.media.ContentMedia;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.hutool.core.bean.BeanUtil;

public final class ContentCards {
    private ContentCards() {}
    public static ContentSummary summary(Content c, ContentMedia media) {
        var summary = BeanUtil.copyProperties(c, ContentSummary.class);
        summary.setType(c.getType().getCode());
        summary.setStatus(c.getStatus().getCode());
        summary.setCoverUrl(c.getCoverObjectKey());
        if (media != null && media.video() != null) {
            summary.setVideoUrl(media.video().objectKey());
            summary.setDurationMs(media.video().durationMs());
        } else if (media != null) summary.setExportDurationMs(media.dynamic().exportDurationMs());
        return summary;
    }
}
