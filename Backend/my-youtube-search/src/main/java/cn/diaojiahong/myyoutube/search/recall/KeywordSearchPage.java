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
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class KeywordSearchPage {
    private final KeywordRecallStrategy keyword;
    private final SearchContentSummaryClient summaries;
    private final RecallProperties properties;

    public CursorPage<ContentSummary, List<FieldValue>> search(SearchContext context, List<FieldValue> position,
                                                              Set<String> excluded, int pageSize) {
        List<ContentSummary> result = new ArrayList<>();
        int checked = 0;
        int rounds = 0;
        boolean more = false;
        String label = context.isTagSearch() ? "标签搜索" : "内容搜索";

        do {
            try {
                int size = Math.min(pageSize - result.size(), properties.getRefillMaxCandidates() - checked);
                RecallBatch batch = keyword.load(context, position, size, excluded);
                if (batch.ids().isEmpty()) { more = false; break; }

                List<ContentSummary> rows = summaries.listByIdsOrdered(new ContentSummaryPort.SummaryQuery(batch.ids(), null));
                result.addAll(rows);

                checked += batch.ids().size();
                more = batch.hasMore();
                if (more) position = batch.nextCursor();
                rounds++;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(label + "已中断", interrupted);
            } catch (Exception failure) {
                if (checked == 0) {
                    if (failure instanceof RuntimeException runtime) throw runtime;
                    throw new IllegalStateException(label + "失败", failure);
                }
                log.warn("{}补取停止 errorType={}", label, failure.getClass().getSimpleName());
                break;
            }
            // 判断是否需要继续补取
        } while (more && result.size() < pageSize && rounds <= properties.getRefillMaxRounds()
                && checked < properties.getRefillMaxCandidates());

        return new CursorPage<>(result, more ? position : null);
    }
}
