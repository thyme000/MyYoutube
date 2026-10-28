package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import co.elastic.clients.elasticsearch._types.FieldValue;
import com.google.gson.JsonArray;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

record ContentSearchCursor(String keyword, String category, boolean latest, Long userId,
                           List<String> firstPageIds, JsonArray sort) {
    static String encode(SearchContext context, List<String> firstPageIds, List<FieldValue> position) {
        ContentSearchCursor cursor = new ContentSearchCursor(context.getKeyword(), context.getCategory(),
                context.isSortByLatest(), context.getUserId(), firstPageIds, RecallSearchClient.sortJson(position));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JsonUtil.toJson(cursor).getBytes(StandardCharsets.UTF_8));
    }

    static ContentSearchCursor decode(String value, SearchContext context) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.length() > 8192) throw new IllegalArgumentException();
            String json = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            ContentSearchCursor cursor = JsonUtil.fromJson(json, ContentSearchCursor.class);
            if (cursor == null || !Objects.equals(cursor.keyword(), context.getKeyword())
                    || !Objects.equals(cursor.category(), context.getCategory())
                    || cursor.latest() != context.isSortByLatest() || !Objects.equals(cursor.userId(), context.getUserId())
                    || cursor.firstPageIds() == null || cursor.firstPageIds().size() > PageQuery.MAX_PAGE_SIZE || cursor.sort() == null)
                throw new IllegalArgumentException();

            for (String id : cursor.firstPageIds()) if (Long.parseLong(id) <= 0) throw new IllegalArgumentException();
            if (cursor.latest() && !cursor.firstPageIds().isEmpty()) throw new IllegalArgumentException();
            if (!cursor.sort().isEmpty()) SearchSortValidation.validate(cursor.sort(), cursor.latest());
            return cursor;
        } catch (RuntimeException invalid) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "内容搜索游标无效，请重新搜索");
        }
    }

    List<FieldValue> position() { return RecallSearchClient.sortValues(sort); }
}
