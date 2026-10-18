package cn.diaojiahong.myyoutube.common.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.LongSerializationPolicy;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 全局唯一 Gson 入口: Long 一律序列化成字符串,
 * 雪花 ID 超出 JS 安全整数, 序列化成数字会被前端 parse 精度丢损。
 * 禁止在业务代码里 new Gson()/new GsonBuilder() 绕开该配置。
 */
public final class JsonUtil {

    public static final Gson GSON = new GsonBuilder()
            .setLongSerializationPolicy(LongSerializationPolicy.STRING)
            .registerTypeAdapter(LocalDateTime.class, new TypeAdapter<LocalDateTime>() {
                @Override public void write(JsonWriter out, LocalDateTime value) throws IOException { out.value(value.toString()); }
                @Override public LocalDateTime read(JsonReader in) throws IOException { return LocalDateTime.parse(in.nextString()); }
            }.nullSafe())
            .create();

    private JsonUtil() {
    }

    public static String toJson(Object src) {
        return GSON.toJson(src);
    }

    public static <T> T fromJson(String json, Class<T> classOfT) {
        return GSON.fromJson(json, classOfT);
    }
}
