package cn.diaojiahong.myyoutube.chat.service.strategy;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatContent;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SystemChatContentStrategy implements ChatContentTypeStrategy {

    @Override
    public Set<ChatContentTypeEnum> types() {
        return Set.of(ChatContentTypeEnum.SYSTEM);
    }

    @Override
    public void validate(ChatContent content) {
        if (content.getSystemText() == null || content.getSystemText().isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "系统消息内容不能为空");
        }
    }
}
