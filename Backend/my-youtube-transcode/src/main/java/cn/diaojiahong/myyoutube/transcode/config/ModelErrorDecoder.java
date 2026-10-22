package cn.diaojiahong.myyoutube.transcode.config;

import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import feign.Response;
import feign.codec.ErrorDecoder;
import java.nio.charset.StandardCharsets;

public final class ModelErrorDecoder implements ErrorDecoder {
    private final ErrorDecoder fallback = new cn.diaojiahong.myyoutube.common.feign.InternalApiErrorDecoder();

    @Override public Exception decode(String methodKey, Response response) {
        if ((response.status() != 503 && !(response.status() == 502 && methodKey.startsWith("ContentPipelinePort#generateTags("))) || response.body() == null
                || !(methodKey.startsWith("AsrPort#") || methodKey.startsWith("OcrPort#")
                    || methodKey.startsWith("ContentPipelinePort#generateTags(")))
            return fallback.decode(methodKey, response);
        byte[] body;
        try (var input = response.body().asInputStream()) {
            body = input.readNBytes(8193);
        } catch (Exception error) { return fallback.decode(methodKey, response); }
        var buffered = response.toBuilder().body(body).build();
        if (body.length > 8192) return fallback.decode(methodKey, buffered);
        try {
            var detail = JsonUtil.GSON.fromJson(new String(body, StandardCharsets.UTF_8), com.google.gson.JsonObject.class)
                    .getAsJsonObject("detail");
            String code = detail.get("code").getAsString();
            boolean tag = methodKey.startsWith("ContentPipelinePort#");
            if (tag && response.status()==502 && "MODEL_EXECUTION_FAILED".equals(code) && detail.has("resource")
                    && "LLM".equals(detail.get("resource").getAsString()))
                return new cn.diaojiahong.myyoutube.common.resilience.ModelCallFailure("LLM",null);
            if (!(ModelUnavailable.ADMISSION_CODES.contains(code) || tag &&
                    ("MODEL_CIRCUIT_OPEN".equals(code) || "MODEL_ADMISSION_BLOCKED".equals(code))))
                return fallback.decode(methodKey, buffered);
            String resource = tag ? "LLM" : methodKey.startsWith("AsrPort#") ? "ASR" : "OCR";
            if (tag && (!detail.has("resource") || !"LLM".equals(detail.get("resource").getAsString())))
                return fallback.decode(methodKey, buffered);
            int delay = 30;
            for (var header : response.headers().entrySet()) if (header.getKey().equalsIgnoreCase("Retry-After")) {
                try { delay = Integer.parseInt(header.getValue().iterator().next()); }
                catch (RuntimeException ignored) { delay = 30; }
            }
            return new ModelUnavailable(resource, code, delay, null);
        } catch (RuntimeException error) { return fallback.decode(methodKey, buffered); }
    }
}
