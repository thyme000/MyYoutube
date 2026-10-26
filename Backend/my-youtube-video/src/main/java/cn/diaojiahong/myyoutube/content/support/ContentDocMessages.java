package cn.diaojiahong.myyoutube.content.support;

import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import java.util.List;

public final class ContentDocMessages {
    private ContentDocMessages() {}
    public static ContentDocMessage of(Content content, List<Long> tagIds, String summary) {
        return ContentDocMessage.builder().type(content.getType().getCode()).contentId(content.getId())
                .indexVersion(content.getIndexVersion()).deleted(Integer.valueOf(1).equals(content.getIsDelete()))
                .title(content.getTitle()).description(content.getDescription()).summary(summary).coverUrl(content.getCoverObjectKey())
                .tags(content.getTags()).categoryCode(content.getCategoryCode()).statusCode(content.getStatus().getCode())
                .visibilityCode(content.getVisibility()).authorId(content.getAuthorId()).createTime(content.getCreateTime())
                .playCount(content.getPlayCount()).likeCount(content.getLikeCount()).tagIds(tagIds).build();
    }
}
