package cn.diaojiahong.myyoutube.common.observability;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class PipelineEventJson {
    private static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    static {
        JSON.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    private PipelineEventJson() {
    }

    public static String encode(PipelineEvent event) {
        try {
            return JSON.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid pipeline event", e);
        }
    }

    public static PipelineEvent decode(String source) {
        try {
            JsonNode tree = JSON.readTree(source);
            if (tree == null || !tree.isObject() || !tree.path("@timestamp").isTextual()
                    || (tree.hasNonNull("nextRetryAt") && !tree.get("nextRetryAt").isTextual())) {
                throw new IllegalArgumentException("Invalid timestamp field");
            }
            return JSON.treeToValue(tree, PipelineEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid pipeline event", e);
        }
    }

    public static String digest(PipelineEvent event) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(encode(event).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
