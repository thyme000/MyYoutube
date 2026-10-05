package cn.diaojiahong.myyoutube.chat.service.strategy;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ChatContentTypeEnum;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 内容类型策略注册表: 按 ChatContentTypeEnum 分派, EnumMap 承载
 */
@Component
public class ChatContentTypeStrategyRegistry {

    private final Map<ChatContentTypeEnum, ChatContentTypeStrategy> strategies = new EnumMap<>(ChatContentTypeEnum.class);

    public ChatContentTypeStrategyRegistry(List<ChatContentTypeStrategy> list) {
        for (ChatContentTypeStrategy strategy : list) {
            strategy.types().forEach(t -> strategies.put(t, strategy));
        }
    }

    /** 类型未注册时返回 null, 调用方自行兜底 */
    public ChatContentTypeStrategy get(ChatContentTypeEnum type) {
        return type == null ? null : strategies.get(type);
    }
}
