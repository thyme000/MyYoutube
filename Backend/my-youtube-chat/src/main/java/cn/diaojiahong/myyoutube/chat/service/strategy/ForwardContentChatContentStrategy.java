package cn.diaojiahong.myyoutube.chat.service.strategy;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatContent;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class ForwardContentChatContentStrategy implements ChatContentTypeStrategy {
    private final ContentInternalPort contents;
    private final S3StorageUtil storage;

    @Override
    public Set<ChatContentTypeEnum> types() {
        return Set.of(ChatContentTypeEnum.FORWARD_CONTENT);
    }

    @Override
    public void validate(ChatContent content) {
        if (content == null || content.getContentId() == null || content.getContentId() <= 0)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "转发内容id无效");
    }

    @Override
    public ChatContent snapshot(ChatContent content) {
        validate(content);
        ContentInternalPort.ForwardInfo info = contents.forwardInfo(
                content.getContentId(),
                Long.valueOf(UserContext.getUserId()));
        if (info == null) throw new BizException(ErrorCode.NOT_FOUND, "内容不存在或不可访问");
        var snapshot = new ChatContent();
        snapshot.setContentId(info.contentId());
        snapshot.setMediaType(info.type());
        snapshot.setTitle(info.title());
        snapshot.setCoverObjectKey(info.coverObjectKey());
        return snapshot;
    }

    @Override
    public void signUrls(ChatContent content, long expireSeconds) {
        String key = content.getCoverObjectKey();
        if (key != null && !key.isBlank()) content.setCoverUrl(storage.signGetUrl(key, expireSeconds));
    }

    @Override
    public String previewLabel(ChatContent content) {
        return "dynamic".equals(content.getMediaType()) ? "[转发动态]" : "[转发视频]";
    }
}
