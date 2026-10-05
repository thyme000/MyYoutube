package cn.diaojiahong.myyoutube.video.port;

import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategy;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ContentSummaryPortImpl implements ContentSummaryPort {

    private final ContentMapper contentMapper;
    private final ContentTypeStrategyRegistry strategyRegistry;

    @Override
    public List<ContentSummary> listByIdsOrdered(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> idList = ids.stream().map(Long::valueOf).toList();
        Map<Long, Content> contents = contentMapper.selectBatchIds(idList).stream()
                .collect(Collectors.toMap(Content::getId, c -> c));

        // 按 content 类型分组, 各类型的取数+过滤+转换收在自己策略里
        Map<ContentTypeEnum, Set<Long>> idsByType = new EnumMap<>(ContentTypeEnum.class);
        for (String id : ids) {
            Content c = contents.get(Long.valueOf(id));
            if (c == null) {
                continue;
            }
            idsByType.computeIfAbsent(c.getType(), k -> new HashSet<>()).add(Long.valueOf(id));
        }
        Map<Long, ContentSummary> summaryMap = new LinkedHashMap<>();
        for (Map.Entry<ContentTypeEnum, Set<Long>> entry : idsByType.entrySet()) {
            ContentTypeStrategy strategy = strategyRegistry.get(entry.getKey());
            if (strategy == null) {
                continue;
            }
            summaryMap.putAll(strategy.fetchSummaries(entry.getValue(), contents));
        }

        List<ContentSummary> ordered = new ArrayList<>(ids.size());
        for (String id : ids) {
            ContentSummary s = summaryMap.get(Long.valueOf(id));
            if (s != null) {
                ordered.add(s);
            }
        }
        return ordered;
    }
}
