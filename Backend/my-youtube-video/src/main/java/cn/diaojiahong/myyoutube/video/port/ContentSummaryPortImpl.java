package cn.diaojiahong.myyoutube.video.port;

import cn.diaojiahong.myyoutube.common.media.ContentMedia;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.content.support.ContentCards;
import cn.diaojiahong.myyoutube.content.service.AudioContentAccess;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ContentSummaryPortImpl implements ContentSummaryPort {
    private final ContentMapper contents;
    private final ContentTypeStrategyRegistry strategies;
    private final AudioContentAccess audioAccess;

    @Override
    public List<ContentSummary> listByIdsOrdered(SummaryQuery query) {
        List<String> ids = query.ids();
        if (ids == null || ids.isEmpty()) return List.of();
        List<Content> rows = contents.selectList(new LambdaQueryWrapper<Content>().in(Content::getId, ids)
                .eq(Content::getIsDelete, 0).eq(Content::getStatus, ContentStatusEnum.PUBLISHED)
                .and(q -> q.eq(Content::getVisibility, "public")
                        .or(query.viewerId() != null).eq(
                                query.viewerId() != null,
                                Content::getAuthorId,
                                query.viewerId())));
        Set<Long> blocked = audioAccess.blocked(rows);
        var byId = new HashMap<Long, ContentSummary>();
        for (Map.Entry<ContentTypeEnum,List<Content>> group : rows.stream()
                .filter(c -> !blocked.contains(c.getId()))
                .collect(Collectors.groupingBy(Content::getType))
                .entrySet()) {
            Map<Long,ContentMedia> media = strategies.get(group.getKey())
                    .loadMedia(group.getValue().stream().map(Content::getId).toList());
            for (Content row : group.getValue()) if (media.containsKey(row.getId())) byId.put(row.getId(), ContentCards.summary(row, media.get(row.getId())));
        }
        return ids.stream().map(Long::valueOf).map(byId::get).filter(Objects::nonNull).toList();
    }
}
