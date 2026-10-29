package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.page.CursorCodec;
import co.elastic.clients.elasticsearch._types.FieldValue;
import com.google.gson.JsonArray;
import java.util.List;
import java.util.Objects;

record TagSearchCursor(String tag, Long userId, JsonArray sort) {
    private static final CursorCodec<TagSearchCursor> CODEC = new CursorCodec<>(
            TagSearchCursor.class, 2048, "标签搜索游标无效，请重新搜索");

    static String encode(SearchContext context, List<FieldValue> values) {
        JsonArray sort = RecallSearchClient.sortJson(values);
        return CODEC.encode(new TagSearchCursor(context.getKeyword(), context.getUserId(), sort));
    }

    static List<FieldValue> decode(String value, SearchContext context) {
        TagSearchCursor cursor = CODEC.decode(value, decoded -> {
            if (!Objects.equals(decoded.tag(), context.getKeyword())
                    || !Objects.equals(decoded.userId(), context.getUserId()) || decoded.sort() == null)
                throw new IllegalArgumentException();
            SearchSortValidation.validate(decoded.sort(), false);
        });
        return cursor == null ? List.of() : RecallSearchClient.sortValues(cursor.sort());
    }
}
