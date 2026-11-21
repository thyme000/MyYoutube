package cn.diaojiahong.myyoutube.chat.service.strategy;

import cn.diaojiahong.myyoutube.chat.domain.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
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

    public ChatContentTypeStrategy require(ChatContentTypeEnum type) {
        ChatContentTypeStrategy strategy = get(type);
        if (strategy == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "消息类型错误");
        }
        return strategy;
    }
}
