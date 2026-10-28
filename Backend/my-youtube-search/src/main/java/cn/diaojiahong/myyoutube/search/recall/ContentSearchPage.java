package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import co.elastic.clients.elasticsearch._types.FieldValue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 内容和标签搜索的统一分页入口，在这里选择混合首批或关键词分页。 */
@Service
@RequiredArgsConstructor
public class ContentSearchPage {
    private final HybridRecallService hybridRecall;
    private final KeywordSearchPage keywordPages;
    private final SearchContentSummaryClient summaries;
    private final RecallProperties properties;

    public CursorPage<ContentSummary, String> search(SearchContext context) {
        if (context.isTagSearch()) return tagPage(context);

        ContentSearchCursor cursor = ContentSearchCursor.decode(context.getQuery().getCursor(), context);
        if (cursor == null && !context.isSortByLatest()) return hybridFirstPage(context);
        return keywordPage(context, cursor);
    }

    private CursorPage<ContentSummary, String> keywordPage(SearchContext context, ContentSearchCursor cursor) {
        List<FieldValue> position = cursor == null ? List.of() : cursor.position();
        List<String> firstPageIds = cursor == null ? List.of() : cursor.firstPageIds();
        CursorPage<ContentSummary, List<FieldValue>> page = keywordPages.search(context, position,
                Set.copyOf(firstPageIds), context.getQuery().getPageSize());
        String next = page.nextCursor() == null ? null : ContentSearchCursor.encode(context, firstPageIds, page.nextCursor());
        return new CursorPage<>(page.items(), next);
    }

    private CursorPage<ContentSummary, String> tagPage(SearchContext context) {
        List<FieldValue> position = TagSearchCursor.decode(context.getQuery().getCursor(), context);
        CursorPage<ContentSummary, List<FieldValue>> page = keywordPages.search(context, position,
                Set.of(), context.getQuery().getPageSize());
        String next = page.nextCursor() == null ? null : TagSearchCursor.encode(context, page.nextCursor());
        return new CursorPage<>(page.items(), next);
    }

    private CursorPage<ContentSummary, String> hybridFirstPage(SearchContext context) {
        Map<String, ContentSummary> hydrated = new HashMap<>();
        int size = Math.max(properties.getFirstPageSize(), context.getQuery().getPageSize());
        HybridRecallService.FirstPage page = hybridRecall.recall(context, size, ids -> {
            List<ContentSummary> rows = summaries.listByIdsOrdered(new ContentSummaryPort.SummaryQuery(ids, null));
            for (ContentSummary row : rows) hydrated.put(row.getId().toString(), row);
            return rows.stream().map(row -> row.getId().toString()).toList();
        });

        // 混合排序可能只展示关键词候选的一部分，续查从头开始并排除已展示项，不能沿用召回批次末尾位置。
        String next = page.hasMore() ? ContentSearchCursor.encode(context, page.ids(), List.of()) : null;
        return new CursorPage<>(page.ids().stream().map(hydrated::get).toList(), next);
    }
}
