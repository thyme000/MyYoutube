package cn.diaojiahong.myyoutube.search.recall;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

final class SearchSortValidation {
    private static final DateTimeFormatter CREATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);

    private SearchSortValidation() {
    }

    static void validate(JsonArray sort, boolean latest) {
        int fields = latest ? 2 : 3;
        if (sort.size() != fields) throw new IllegalArgumentException();
        if (!latest && !number(sort.get(0))) throw new IllegalArgumentException();

        JsonElement id = sort.get(fields - 1);
        if (!number(id) || id.getAsBigDecimal().longValueExact() <= 0) throw new IllegalArgumentException();

        // 日期排序值可能是 epoch_millis 或映射声明的日期文本；null 表示缺失日期。
        JsonElement time = sort.get(fields - 2);
        if (time.isJsonNull()) return;
        if (number(time)) time.getAsBigDecimal().longValueExact();
        else if (time.isJsonPrimitive() && time.getAsJsonPrimitive().isString()) {
            String value = time.getAsString();
            if (value.matches("-?\\d+")) Long.parseLong(value);
            else LocalDateTime.parse(value, CREATE_TIME);
        } else throw new IllegalArgumentException();
    }

    private static boolean number(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }
}
