package cn.diaojiahong.myyoutube.chat.service.strategy;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatContent;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.video.api.VideoInternalPort;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class ForwardDynamicChatContentStrategy implements ChatContentTypeStrategy {

    private final VideoInternalPort videoInternalPort;
    private final S3StorageUtil storageUtil;

    public ForwardDynamicChatContentStrategy(VideoInternalPort videoInternalPort, S3StorageUtil storageUtil) {
        this.videoInternalPort = videoInternalPort;
        this.storageUtil = storageUtil;
    }

    @Override
    public Set<ChatContentTypeEnum> types() {
        return Set.of(ChatContentTypeEnum.FORWARD_DYNAMIC);
    }

    @Override
    public void validate(ChatContent content) {
        if (content.getForwardDynamicId() == null) {
            throw new BizException(BizException.MESSAGE_ERROR, "转发动态id不能为空");
        }
    }

    /** 落库时固化标题与首帧, 与转发视频同一套不可变卡片语义 */
    @Override
    public ChatContent snapshot(ChatContent content) {
        VideoInternalPort.ForwardDynamicInfo dynamic = videoInternalPort.getForwardDynamic(content.getForwardDynamicId());
        if (dynamic == null) {
            throw new BizException(BizException.MESSAGE_ERROR, "动态不存在");
        }
        ChatContent chatContent = new ChatContent();
        chatContent.setForwardDynamicCoverObjectKey(dynamic.getCoverObjectKey());
        chatContent.setForwardDynamicId(content.getForwardDynamicId());
        chatContent.setForwardDynamicTitle(dynamic.getTitle());
        return chatContent;
    }

    @Override
    public void signUrls(ChatContent content, long expireSeconds) {
        if (content.getForwardDynamicCoverObjectKey() != null && !content.getForwardDynamicCoverObjectKey().isBlank()) {
            content.setForwardDynamicCoverObjectKey(storageUtil.signGetUrl(content.getForwardDynamicCoverObjectKey(), expireSeconds));
        }
    }

    @Override
    public String previewLabel(ChatContent content) {
        return "[转发动态]";
    }
}
