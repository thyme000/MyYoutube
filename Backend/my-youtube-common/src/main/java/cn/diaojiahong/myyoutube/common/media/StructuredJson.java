package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;

public final class StructuredJson {
    private StructuredJson() {
    }

    public static JsonObject strictObject(String text) {
        if (text == null) {
            throw new IllegalArgumentException("模型输出为空");
        }

        try {
            JsonFactory factory = com.fasterxml.jackson.core.JsonFactory.builder()
                    .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
            ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper(factory)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode node = mapper.readTree(text);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("模型输出须为 JSON 对象");
            }
            return JsonUtil.GSON.fromJson(node.toString(), JsonObject.class);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("模型输出 JSON 非法", e);
        }
    }

    public static JsonObject object(String text) {
        if (text == null) {
            throw new IllegalArgumentException("模型输出为空");
        }

        String value = text.trim();
        if (value.startsWith("```")) {
            int line = value.indexOf('\n');
            if (line < 0 || !value.endsWith("```")) {
                throw new IllegalArgumentException("模型输出格式非法");
            }
            value = value.substring(line + 1, value.length() - 3).trim();
        }

        JsonObject object = JsonUtil.fromJson(value, JsonObject.class);
        if (object == null) {
            throw new IllegalArgumentException("模型输出为空");
        }

        return object;
    }
}
