package cn.diaojiahong.myyoutube.chat.service.strategy;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatContent;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.video.api.VideoInternalPort;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class ForwardVideoChatContentStrategy implements ChatContentTypeStrategy {

    private final VideoInternalPort videoInternalPort;
    private final S3StorageUtil storageUtil;

    public ForwardVideoChatContentStrategy(VideoInternalPort videoInternalPort, S3StorageUtil storageUtil) {
        this.videoInternalPort = videoInternalPort;
        this.storageUtil = storageUtil;
    }

    @Override
    public Set<ChatContentTypeEnum> types() {
        return Set.of(ChatContentTypeEnum.FORWARD_VIDEO);
    }

    @Override
    public void validate(ChatContent content) {
        if (content.getForwardVideoId() == null) {
            throw new BizException(BizException.MESSAGE_ERROR, "转发视频id不能为空");
        }
    }

    /** 落库时固化标题与封面, 原视频后续改动/删除不影响已发消息 */
    @Override
    public ChatContent snapshot(ChatContent content) {
        VideoInternalPort.ForwardVideoInfo video = videoInternalPort.getForwardVideo(content.getForwardVideoId());
        if (video == null) {
            throw new BizException(BizException.MESSAGE_ERROR, "视频不存在");
        }
        ChatContent chatContent = new ChatContent();
        chatContent.setForwardVideoCoverObjectKey(video.getCoverUrl());
        chatContent.setForwardVideoId(content.getForwardVideoId());
        chatContent.setForwardVideoTitle(video.getTitle());
        return chatContent;
    }

    @Override
    public void signUrls(ChatContent content, long expireSeconds) {
        if (content.getForwardVideoCoverObjectKey() != null && !content.getForwardVideoCoverObjectKey().isBlank()) {
            content.setForwardVideoCoverObjectKey(storageUtil.signGetUrl(content.getForwardVideoCoverObjectKey(), expireSeconds));
        }
    }

    @Override
    public String previewLabel(ChatContent content) {
        return "[转发视频]";
    }
}
