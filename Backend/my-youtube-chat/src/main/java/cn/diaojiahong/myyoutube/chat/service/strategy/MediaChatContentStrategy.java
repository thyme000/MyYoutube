package cn.diaojiahong.myyoutube.chat.service.strategy;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatContent;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class MediaChatContentStrategy implements ChatContentTypeStrategy {

    private final S3StorageUtil storageUtil;

    public MediaChatContentStrategy(S3StorageUtil storageUtil) {
        this.storageUtil = storageUtil;
    }

    @Override
    public Set<ChatContentTypeEnum> types() {
        return Set.of(ChatContentTypeEnum.IMAGE, ChatContentTypeEnum.FILE);
    }

    @Override
    public void validate(ChatContent content) {
        if (content.getObjectKey() == null || content.getObjectKey().isBlank()
                || content.getMimeType() == null || content.getMimeType().isBlank()
                || content.getSize() == null || content.getSize() <= 0) {
            throw new BizException(BizException.MESSAGE_ERROR, "媒体消息字段不完整");
        }
    }

    @Override
    public void signUrls(ChatContent content, long expireSeconds) {
        if (content.getObjectKey() != null && !content.getObjectKey().isBlank()) {
            content.setObjectKey(storageUtil.signGetUrl(content.getObjectKey(), expireSeconds));
        }
    }

    @Override
    public String previewLabel(ChatContent content) {
        return "[媒体文件]";
    }
}
