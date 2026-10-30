package cn.diaojiahong.myyoutube.common.feign;

import cn.diaojiahong.myyoutube.common.resilience.ModelCallFailure;
import cn.diaojiahong.myyoutube.common.resilience.ModelErrorData;
import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import feign.FeignException;
import feign.Response;
import feign.codec.ErrorDecoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

public final class ModelErrorDecoder implements ErrorDecoder {
    public enum Protocol {
        INTERNAL, FASTAPI
    }

    private static final int MAX_BODY_BYTES = 8192;
    private final Protocol protocol;

    private final ErrorDecoder fallback = new InternalApiErrorDecoder();

    public ModelErrorDecoder(Protocol protocol) {
        this.protocol = protocol;
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        String resource = resource(methodKey);
        if (resource == null || response.body() == null
                || response.status() != 503 && !(protocol == Protocol.INTERNAL && response.status() == 502))
            return fallback.decode(methodKey, response);
        byte[] bytes;
        try (Response.Body body = response.body()) {
            bytes = body.asInputStream().readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException error) {
            return FeignFailures.fromStatus(response.status(), error);
        }
        Response buffered = response.toBuilder().body(bytes).build();
        if (bytes.length <= MAX_BODY_BYTES) {
            try {
                JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                ModelErrorData data = protocol == Protocol.INTERNAL ? internal(json, response.status()) : fastapi(
                        json,
                        resource);
                if (data != null && resource.equals(data.resource())) {
                    FeignException cause = FeignException.errorStatus(methodKey, buffered);
                    if (response.status() == 502 && "MODEL_EXECUTION_FAILED".equals(data.reason()))
                        return new ModelCallFailure(resource, cause);
                    if (response.status() == 503 && (ModelUnavailable.ADMISSION_CODES.contains(data.reason())
                            || protocol == Protocol.INTERNAL && ("MODEL_CIRCUIT_OPEN".equals(data.reason())
                            || "MODEL_ADMISSION_BLOCKED".equals(data.reason()))))
                        return new ModelUnavailable(resource, data.reason(), retryAfter(response), cause);
                }
            } catch (RuntimeException ignored) {
                // Only the selected source protocol may change model scheduling.
            }
        }
        return fallback.decode(methodKey, buffered);
    }

    private String resource(String methodKey) {
        if (protocol == Protocol.INTERNAL)
            return "ContentPipelinePort#generateTags(TagRequest)".equals(methodKey) ? "LLM" : null;
        return switch (methodKey) {
            case "AsrPort#transcribe(Request)" -> "ASR";
            case "OcrPort#recognize(Request)" -> "OCR";
            default -> null;
        };
    }

    private ModelErrorData internal(JsonObject json, int status) {
        JsonElement code = json.get("code");
        ErrorCode expected = status == 503 ? ErrorCode.MODEL_UNAVAILABLE : ErrorCode.MODEL_EXECUTION_FAILED;
        if (code == null || !code.isJsonPrimitive() || !code.getAsJsonPrimitive().isNumber()
                || code.getAsBigDecimal().intValueExact() != expected.getCode()) {
            return null;
        }
        String message = string(json, "message");
        if (message == null || message.isBlank() || message.length() > 512) return null;
        JsonObject data = json.getAsJsonObject("data");
        String resource = string(data, "resource"), reason = string(data, "reason");
        return resource == null || reason == null ? null : new ModelErrorData(resource, reason);
    }

    private ModelErrorData fastapi(JsonObject json, String expectedResource) {
        JsonObject detail = json.getAsJsonObject("detail");
        String reason = string(detail, "code");
        if (reason == null) return null;
        if (detail.has("resource") && !expectedResource.equals(string(detail, "resource"))) return null;
        return new ModelErrorData(expectedResource, reason);
    }

    private String string(JsonObject json, String name) {
        JsonElement value = json == null ? null : json.get(name);
        return value != null
                && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private int retryAfter(Response response) {
        for (Map.Entry<String, Collection<String>> header : response.headers().entrySet()) {
            if (header.getKey().equalsIgnoreCase("Retry-After")) {
                try {
                    return Integer.parseInt(header.getValue().iterator().next());
                } catch (RuntimeException ignored) {
                    return 30;
                }
            }
        }
        return 30;
    }
}
