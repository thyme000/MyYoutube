package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.page.CursorCodec;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import co.elastic.clients.elasticsearch._types.FieldValue;
import com.google.gson.JsonArray;
import java.util.List;
import java.util.Objects;

record ContentSearchCursor(String keyword, String category, boolean latest, Long userId,
                           List<String> firstPageIds, JsonArray sort) {
    private static final CursorCodec<ContentSearchCursor> CODEC = new CursorCodec<>(
            ContentSearchCursor.class, 8192, "内容搜索游标无效，请重新搜索");

    static String encode(SearchContext context, List<String> firstPageIds, List<FieldValue> position) {
        ContentSearchCursor cursor = new ContentSearchCursor(context.getKeyword(), context.getCategory(),
                context.isSortByLatest(), context.getUserId(), firstPageIds, RecallSearchClient.sortJson(position));
        return CODEC.encode(cursor);
    }

    static ContentSearchCursor decode(String value, SearchContext context) {
        return CODEC.decode(value, cursor -> {
            if (!Objects.equals(cursor.keyword(), context.getKeyword())
                    || !Objects.equals(cursor.category(), context.getCategory())
                    || cursor.latest() != context.isSortByLatest()
                    || !Objects.equals(cursor.userId(), context.getUserId())
                    || cursor.firstPageIds() == null
                    || cursor.firstPageIds().size() > PageQuery.MAX_PAGE_SIZE
                    || cursor.sort() == null)
                throw new IllegalArgumentException();

            for (String id : cursor.firstPageIds()) if (Long.parseLong(id) <= 0) throw new IllegalArgumentException();
            if (cursor.latest() && !cursor.firstPageIds().isEmpty()) throw new IllegalArgumentException();
            if (!cursor.sort().isEmpty()) SearchSortValidation.validate(cursor.sort(), cursor.latest());
        });
    }

    List<FieldValue> position() {
        return RecallSearchClient.sortValues(sort);
    }
}
