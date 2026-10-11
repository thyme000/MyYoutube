package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import com.google.gson.JsonObject;

public final class StructuredJson {
    private StructuredJson() {}
    public static JsonObject object(String text) {
        if (text == null) throw new IllegalArgumentException("模型输出为空");
        String value = text.trim();
        if (value.startsWith("```")) {
            int line = value.indexOf('\n');
            if (line < 0 || !value.endsWith("```")) throw new IllegalArgumentException("模型输出格式非法");
            value = value.substring(line + 1, value.length() - 3).trim();
        }
        JsonObject object = JsonUtil.fromJson(value, JsonObject.class);
        if (object == null) throw new IllegalArgumentException("模型输出为空");
        return object;
    }
}
