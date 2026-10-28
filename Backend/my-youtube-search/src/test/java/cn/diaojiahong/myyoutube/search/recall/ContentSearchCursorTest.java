package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.ContentSearchCursorQuery;
import co.elastic.clients.elasticsearch._types.FieldValue;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ContentSearchCursorTest {
    SearchContext context(String keyword, String category, boolean latest, Long user) {
        ContentSearchCursorQuery query = new ContentSearchCursorQuery();
        query.setKeyword(keyword); query.setCategory(category); query.setSortByLatest(latest);
        return SearchContext.of(query, user, List.of());
    }

    @Test void cursorPreservesLargeIdsAndChecksTheSearchConditions() {
        SearchContext context = context("camera", "video", false, 7L);
        long id = 9007199254740993L;
        String cursor = ContentSearchCursor.encode(context, List.of(Long.toString(id)),
                List.of(FieldValue.of(1.5), FieldValue.of("2026-01-01 00:00:00"), FieldValue.of(id)));
        ContentSearchCursor decoded = ContentSearchCursor.decode(cursor, context);
        assertEquals(id, decoded.position().get(2).longValue());
        assertEquals(List.of(Long.toString(id)), decoded.firstPageIds());
        assertThrows(BizException.class, () -> ContentSearchCursor.decode(cursor, context("different", "video", false, 7L)));
        assertThrows(BizException.class, () -> ContentSearchCursor.decode(cursor, context("camera", "dynamic", false, 7L)));
        assertThrows(BizException.class, () -> ContentSearchCursor.decode(cursor, context("camera", "video", true, 7L)));
        assertThrows(BizException.class, () -> ContentSearchCursor.decode(cursor, context("camera", "video", false, 8L)));
        assertThrows(BizException.class, () -> ContentSearchCursor.decode("invalid", context));
    }

    @Test void rejectsInvalidDatesInBothSortOrders() {
        for (boolean latest : List.of(false, true)) {
            SearchContext context = context("camera", "video", latest, 7L);
            for (String time : List.of("not-a-date", "2026-02-30 00:00:00")) {
                List<FieldValue> sort = latest ? List.of(FieldValue.of(time), FieldValue.of(2L))
                        : List.of(FieldValue.of(1.5), FieldValue.of(time), FieldValue.of(2L));
                String cursor = ContentSearchCursor.encode(context, List.of(), sort);
                assertThrows(BizException.class, () -> ContentSearchCursor.decode(cursor, context));
            }
        }
    }

    @Test void acceptsDateFormatsAndMissingDatesButRejectsFractionalTimestamps() {
        SearchContext context = context("camera", "video", false, 7L);
        for (FieldValue time : List.of(FieldValue.of(1767225600000L), FieldValue.of("1767225600000"),
                FieldValue.of("2026-01-01 00:00:00"), FieldValue.NULL)) {
            String cursor = ContentSearchCursor.encode(context, List.of(), List.of(FieldValue.of(1.5), time, FieldValue.of(2L)));
            FieldValue decoded = ContentSearchCursor.decode(cursor, context).position().get(1);
            assertEquals(RecallSearchClient.sortJson(List.of(time)), RecallSearchClient.sortJson(List.of(decoded)));
        }
        String invalid = ContentSearchCursor.encode(context, List.of(),
                List.of(FieldValue.of(1.5), FieldValue.of(1767225600000.5), FieldValue.of(2L)));
        assertThrows(BizException.class, () -> ContentSearchCursor.decode(invalid, context));
    }

    @Test void firstPageIdsUseTheSameLimitAsPageSize() {
        SearchContext context = context("camera", "video", false, 7L);
        List<String> ids = IntStream.rangeClosed(1, PageQuery.MAX_PAGE_SIZE).mapToObj(Integer::toString).toList();
        String cursor = ContentSearchCursor.encode(context, ids, List.of());
        assertEquals(ids, ContentSearchCursor.decode(cursor, context).firstPageIds());

        List<String> tooMany = IntStream.rangeClosed(1, PageQuery.MAX_PAGE_SIZE + 1).mapToObj(Integer::toString).toList();
        String invalid = ContentSearchCursor.encode(context, tooMany, List.of());
        assertThrows(BizException.class, () -> ContentSearchCursor.decode(invalid, context));
    }
}
