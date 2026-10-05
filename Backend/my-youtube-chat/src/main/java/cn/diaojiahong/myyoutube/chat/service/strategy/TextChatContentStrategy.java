package cn.diaojiahong.myyoutube.chat.service.strategy;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatContent;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class TextChatContentStrategy implements ChatContentTypeStrategy {

    @Override
    public Set<ChatContentTypeEnum> types() {
        return Set.of(ChatContentTypeEnum.TEXT);
    }

    @Override
    public void validate(ChatContent content) {
        if (content.getText() == null || content.getText().isBlank()) {
            throw new BizException(BizException.MESSAGE_ERROR, "文本消息text不能为空");
        }
    }

    @Override
    public String previewLabel(ChatContent content) {
        return content.getText();
    }
}
