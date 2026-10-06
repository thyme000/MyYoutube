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
        private String content;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ChatResponse {
        private String content;
    }
}
