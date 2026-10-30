package cn.diaojiahong.myyoutube.common.feign;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import feign.FeignException;
import feign.MethodMetadata;
import feign.RequestTemplate;
import feign.Response;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class InternalApiErrorDecoder implements ErrorDecoder {
    private static final int MAX_BODY_BYTES = 8192;

    @Override
    public Exception decode(String methodKey, Response response) {
        byte[] bytes;
        try (Response.Body body = response.body()) {
            bytes = body == null ? new byte[0] : body.asInputStream().readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException error) {
            return FeignFailures.fromStatus(response.status(), error);
        }
        FeignException cause = FeignException.errorStatus(methodKey, response.toBuilder().body(bytes).build());
        if (bytes.length <= MAX_BODY_BYTES) {
            try {
                JsonElement json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                if (json.isJsonObject()) {
                    JsonObject object = json.getAsJsonObject();
                    JsonElement rawCode = object.get("code");
                    if (rawCode != null && rawCode.isJsonPrimitive() && rawCode.getAsJsonPrimitive().isNumber()) {
                        int value = rawCode.getAsBigDecimal().intValueExact();
                        ErrorCode code = Arrays.stream(ErrorCode.values())
                                .filter(item -> item.getCode() == value).findFirst().orElse(null);
                        if (code != null && code.getHttpStatus() == response.status() && allows(response, code)) {
                            JsonElement message = object.get("message");
                            if (message != null
                                    && message.isJsonPrimitive()
                                    && message.getAsJsonPrimitive().isString()) {
                                String text = message.getAsString();
                                if (!text.isBlank() && text.length() <= 512) {
                                    return new BizException(code, text, cause);
                                }
                            }
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // An invalid error body is a dependency failure, never a business rejection.
            }
        }
        return FeignFailures.fromStatus(response.status(), cause);
    }

    private boolean allows(Response response, ErrorCode code) {
        if (code.getHttpStatus() >= 500 || code == ErrorCode.INVALID_REQUEST || code == ErrorCode.ENDPOINT_NOT_FOUND
                || code == ErrorCode.UNAUTHORIZED || code == ErrorCode.FORBIDDEN) {
            return false;
        }

        RequestTemplate template = response.request().requestTemplate();
        MethodMetadata metadata = template == null ? null : template.methodMetadata();
        if (metadata == null || metadata.method() == null) {
            return false;
        }

        PropagateBusinessErrors policy = metadata.method().getAnnotation(PropagateBusinessErrors.class);
        if (policy == null) {
            policy = metadata.targetType().getAnnotation(PropagateBusinessErrors.class);
        }
        return policy != null && Arrays.asList(policy.value()).contains(code);
    }
}
