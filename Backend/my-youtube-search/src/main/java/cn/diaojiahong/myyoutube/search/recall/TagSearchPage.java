package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import co.elastic.clients.elasticsearch._types.FieldValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TagSearchPage {
    private final KeywordRecallStrategy keyword;
    private final SearchContentSummaryClient summaries;
    private final RecallProperties properties;

    public CursorPage<ContentSummary, String> search(SearchContext context, String cursor, int pageSize) {
        List<FieldValue> position = TagSearchCursor.decode(cursor, context);
        List<ContentSummary> result = new ArrayList<>();
        int checked = 0;
        int rounds = 0;
        boolean more = true;
        while (more && result.size() < pageSize && rounds <= properties.getRefillMaxRounds()
                && checked < properties.getRefillMaxCandidates()) {
            try {
                int size = Math.min(pageSize - result.size(), properties.getRefillMaxCandidates() - checked);
                RecallBatch batch = keyword.load(context, position, size);
                if (batch.ids().isEmpty()) { more = false; break; }
                List<ContentSummary> rows = summaries.listByIdsOrdered(new ContentSummaryPort.SummaryQuery(batch.ids(), null));
                result.addAll(rows);
                checked += batch.ids().size();
                more = batch.hasMore();
                if (more) position = batch.nextCursor();
                rounds++;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("标签搜索已中断", interrupted);
            } catch (Exception failure) {
                if (checked == 0) {
                    if (failure instanceof RuntimeException runtime) throw runtime;
                    throw new IllegalStateException("标签搜索失败", failure);
                }
                log.warn("Tag search refill stopped errorType={}", failure.getClass().getSimpleName());
                break;
            }
        }
        return new CursorPage<>(result, more ? TagSearchCursor.encode(context, position) : null);
    }
}
