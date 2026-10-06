package cn.diaojiahong.myyoutube.recommend.auto;

import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 打标策略注册表: 按内容池 type 分派
 */
@Component
public class TaggingStrategyRegistry {

    private final Map<ContentTypeEnum, ContentTaggingStrategy> strategies = new EnumMap<>(ContentTypeEnum.class);

    public TaggingStrategyRegistry(List<ContentTaggingStrategy> list) {
        for (ContentTaggingStrategy strategy : list) {
            strategies.put(strategy.type(), strategy);
        }
    }

    public ContentTaggingStrategy get(ContentTypeEnum type) {
        return type == null ? null : strategies.get(type);
    }
}
