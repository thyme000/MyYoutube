package cn.diaojiahong.myyoutube.content.support;

import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Dynamic;
import cn.diaojiahong.myyoutube.video.domain.po.Video;

import java.util.List;

/**
 * 领域对象 → ES 内容文档消息的组装工厂: 发布/动态两条链路原先各写一遍 builder,
 * 字段增减只改这里, 发送拓扑在 common 的 EsSyncPublisher。
 */
public final class ContentDocMessages {

    private ContentDocMessages() {
    }

    public static ContentDocMessage of(Video video, List<Long> tagIds) {
        return ContentDocMessage.builder()
                .type(ContentTypeEnum.VIDEO.getCode()).contentId(video.getId())
                .title(video.getTitle()).description(video.getDescription())
                .coverUrl(video.getCoverUrl()).tags(video.getTags())
                .categoryCode(video.getCategory() != null ? video.getCategory().getCode() : null)
                .statusCode(video.getStatus() != null ? video.getStatus().getCode() : null)
                .visibilityCode(video.getVisibility() != null ? video.getVisibility().getCode() : null)
                .authorId(video.getAuthorId())
                .createTime(video.getCreateTime()).tagIds(tagIds)
                .build();
    }

    /** 动态无简介/分类字段, 首图 objectKey 作封面, 发布态恒为 public+published */
    public static ContentDocMessage of(Dynamic dynamic, String firstImageKey, Long authorId, List<Long> tagIds) {
        return ContentDocMessage.builder()
                .type(ContentTypeEnum.DYNAMIC.getCode()).contentId(dynamic.getId())
                .title(dynamic.getTitle())
                .coverUrl(firstImageKey)
                .tags(dynamic.getTags())
                .statusCode(VideoStatusEnum.PUBLISHED.getCode())
                .visibilityCode("public")
                .authorId(authorId)
                .createTime(dynamic.getCreateTime()).tagIds(tagIds)
                .build();
    }
}
