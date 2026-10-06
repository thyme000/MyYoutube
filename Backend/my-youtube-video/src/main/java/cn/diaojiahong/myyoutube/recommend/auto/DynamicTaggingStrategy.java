package cn.diaojiahong.myyoutube.recommend.auto;

import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.content.support.ContentDocMessages;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Dynamic;
import cn.diaojiahong.myyoutube.video.mapper.DynamicMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DynamicTaggingStrategy implements ContentTaggingStrategy {

    private final DynamicMapper dynamicMapper;
    private final ObjectMapper objectMapper;

    @Override
    public ContentTypeEnum type() {
        return ContentTypeEnum.DYNAMIC;
    }

    @Override
    public TagSignal loadSignal(Long contentId) {
        Dynamic dynamic = dynamicMapper.selectById(contentId);
        if (dynamic == null || (dynamic.getIsDelete() != null && dynamic.getIsDelete() == 1)) {
            return null;
        }
        String title = dynamic.getTitle() == null ? "" : dynamic.getTitle().trim();
        String description = dynamic.getDescription() == null ? "" : dynamic.getDescription().trim();
        if (title.isEmpty() && description.isEmpty()) {
            return null;
        }
        return new TagSignal(title, description);
    }

    @Override
    public void persistResult(Long contentId, String categoryCode, String tagsCache) {
        dynamicMapper.updateById(new Dynamic()
                .setId(contentId)
                .setCategoryCode(categoryCode)
                .setTags(tagsCache));
    }

    @Override
    public ContentDocMessage buildEsMessage(Long contentId, List<Long> tagIds) {
        Dynamic dynamic = dynamicMapper.selectById(contentId);
        if (dynamic == null || (dynamic.getIsDelete() != null && dynamic.getIsDelete() == 1)) {
            return null;
        }
        return ContentDocMessages.of(dynamic, firstImageKey(dynamic), dynamic.getUserId(), tagIds);
    }

    /** 首图 objectKey 作封面 */
    private String firstImageKey(Dynamic dynamic) {
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(dynamic.getTimelineJson(),
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> m : raw) {
                Object key = m.get("imageObjectKey");
                if (key != null && StringUtils.hasText(key.toString())) {
                    return key.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
