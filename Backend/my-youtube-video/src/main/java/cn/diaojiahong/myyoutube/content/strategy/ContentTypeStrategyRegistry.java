package cn.diaojiahong.myyoutube.content.strategy;

import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 内容类型策略注册表: 按内容池 type 分派
 */
@Component
public class ContentTypeStrategyRegistry {

    private final Map<ContentTypeEnum, ContentTypeStrategy> strategies = new EnumMap<>(ContentTypeEnum.class);

    public ContentTypeStrategyRegistry(List<ContentTypeStrategy> list) {
        for (ContentTypeStrategy strategy : list) {
            strategies.put(strategy.type(), strategy);
        }
    }

    public ContentTypeStrategy get(ContentTypeEnum type) {
        return type == null ? null : strategies.get(type);
    }
}
