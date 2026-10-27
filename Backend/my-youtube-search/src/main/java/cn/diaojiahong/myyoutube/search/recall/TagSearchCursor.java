package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import co.elastic.clients.elasticsearch._types.FieldValue;
import com.google.gson.JsonArray;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

record TagSearchCursor(String tag, Long userId, JsonArray sort) {
    static String encode(SearchContext context, List<FieldValue> values) {
        JsonArray sort = new JsonArray();
        for (FieldValue value : values) {
            if (value.isLong()) sort.add(value.longValue());
            else if (value.isDouble()) sort.add(value.doubleValue());
            else if (value.isString()) sort.add(value.stringValue());
            else if (value.isNull()) sort.add((String) null);
            else throw new IllegalStateException("标签搜索排序值无效");
        }
        String json = JsonUtil.toJson(new TagSearchCursor(context.getKeyword(), context.getUserId(), sort));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    static List<FieldValue> decode(String value, SearchContext context) {
        if (value == null || value.isBlank()) return List.of();
        try {
            if (value.length() > 2048) throw new IllegalArgumentException();
            String json = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            TagSearchCursor cursor = JsonUtil.fromJson(json, TagSearchCursor.class);
            if (cursor == null || !Objects.equals(cursor.tag(), context.getKeyword())
                    || !Objects.equals(cursor.userId(), context.getUserId()) || cursor.sort() == null
                    || cursor.sort().size() != 3 || !cursor.sort().get(0).isJsonPrimitive()
                    || !cursor.sort().get(0).getAsJsonPrimitive().isNumber()
                    || !cursor.sort().get(2).isJsonPrimitive() || !cursor.sort().get(2).getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException();
            return RecallSearchClient.sortValues(cursor.sort());
        } catch (RuntimeException invalid) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "标签搜索游标无效，请重新搜索");
        }
    }
}
