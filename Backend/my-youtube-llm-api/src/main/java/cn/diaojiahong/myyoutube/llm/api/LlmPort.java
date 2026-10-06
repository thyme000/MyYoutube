package cn.diaojiahong.myyoutube.llm.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * llm 服务对外契约: 通用 AI 能力(chat 代理, 后续 embedding/ASR 扩展位), 不经网关暴露。
 * 业务编排(prompt/防线/落库)留在调用方, 本服务只做厂商无关的模型调用。
 */
@FeignClient(name = "my-youtube-llm-service", contextId = "llmPort",
        path = "/internal/llm", url = "${my-youtube.llm-service.url:}")
public interface LlmPort {

    @PostMapping("/chat")
    ChatResponse chat(@RequestBody ChatRequest request);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ChatRequest {
        private List<Message> messages;
        /** 可选覆盖模型(vision 等场景), 不传用服务端配置 */
        private String model;
        /** 可选, 不传用服务端配置 */
        private Double temperature;
        /** 可选, 不传用服务端配置 */
        private Integer maxTokens;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class Message {
        /** system/user/assistant */
        private String role;
        /** 纯文本消息 */
        private String content;
        /** 多模态消息(与 content 二选一) */
        private List<ContentPart> parts;

        public static Message asText(String role, String content) {
            return new Message(role, content, null);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ContentPart {
        /** text | image_url */
        private String type;
        private String text;
        private ImageUrl imageUrl;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ImageUrl {
            /** http(s) URL 或 data:base64 内嵌; 云端厂商由 llm-svc 按 cloud 标志代拉 URL 转 base64 */
            private String url;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ChatResponse {
        private String content;
    }
}
