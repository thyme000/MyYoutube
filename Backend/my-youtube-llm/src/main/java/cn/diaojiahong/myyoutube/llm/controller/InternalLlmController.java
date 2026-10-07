package cn.diaojiahong.myyoutube.llm.controller;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.llm.LlmClient;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.llm.embedding.EmbeddingOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * llm 服务内部接口: 通用 chat 代理与向量化, 供各服务经 Feign 调用, 不经网关暴露
 */
@RestController
@RequestMapping("/internal/llm")
@RequiredArgsConstructor
public class InternalLlmController {

    private final LlmClient llmClient;
    private final EmbeddingOrchestrator embeddingOrchestrator;

    @PostMapping("/chat")
    public LlmPort.ChatResponse chat(@RequestBody LlmPort.ChatRequest request) {
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new BizException(BizException.BIZ_ERROR, "messages 不能为空");
        }
        List<LlmClient.Message> messages = request.getMessages().stream()
                .filter(m -> m != null && StringUtils.hasText(m.getRole()))
                .map(this::toClientMessage)
                .toList();
        if (messages.isEmpty()) {
            throw new BizException(BizException.BIZ_ERROR, "messages 不能为空");
        }
        String content = llmClient.chat(messages, request.getModel(), request.getTemperature(), request.getMaxTokens());
        return new LlmPort.ChatResponse(content);
    }

    @PostMapping("/embeddings")
    public LlmPort.EmbeddingsResponse embeddings(@RequestBody LlmPort.EmbeddingsRequest request) {
        if (request == null || request.getTexts() == null || request.getTexts().isEmpty()) {
            throw new BizException(BizException.BIZ_ERROR, "texts 不能为空");
        }
        EmbeddingOrchestrator.EmbeddingResult result = embeddingOrchestrator.embedAll(request.getTexts());
        List<List<Float>> vectors = result.vectors().stream()
                .map(vec -> {
                    List<Float> list = new java.util.ArrayList<>(vec.length);
                    for (float v : vec) {
                        list.add(v);
                    }
                    return list;
                })
                .toList();
        return new LlmPort.EmbeddingsResponse(vectors, result.dims(), result.version());
    }

    private LlmClient.Message toClientMessage(LlmPort.Message m) {
        boolean hasParts = m.getParts() != null && !m.getParts().isEmpty();
        if (!hasParts) {
            return LlmClient.Message.text(m.getRole(), m.getContent() == null ? "" : m.getContent());
        }
        List<LlmClient.Message.Part> parts = m.getParts().stream()
                .filter(p -> p != null && StringUtils.hasText(p.getType()))
                .map(p -> new LlmClient.Message.Part(p.getType(), p.getText(),
                        p.getImageUrl() == null ? null : p.getImageUrl().getUrl()))
                .toList();
        if (!StringUtils.hasText(m.getContent()) && parts.isEmpty()) {
            throw new BizException(BizException.BIZ_ERROR, "消息缺少内容");
        }
        return new LlmClient.Message(m.getRole(), m.getContent(), parts);
    }
}
