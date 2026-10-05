package cn.diaojiahong.myyoutube.chat.notify;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 会话通知策略注册表: 按会话形态分派
 */
@Component
public class ConversationNotifyRegistry {

    private final Map<ConversationTypeEnum, ConversationNotifyStrategy> strategies = new EnumMap<>(ConversationTypeEnum.class);

    public ConversationNotifyRegistry(List<ConversationNotifyStrategy> list) {
        for (ConversationNotifyStrategy strategy : list) {
            strategies.put(strategy.type(), strategy);
        }
    }

    public ConversationNotifyStrategy get(ConversationTypeEnum type) {
        return type == null ? null : strategies.get(type);
    }
}
