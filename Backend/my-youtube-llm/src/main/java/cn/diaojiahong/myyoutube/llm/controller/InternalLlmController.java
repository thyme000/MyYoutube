package cn.diaojiahong.myyoutube.llm.controller;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.llm.LlmClient;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * llm 服务内部接口: 通用 chat 代理, 供各服务经 Feign 调用, 不经网关暴露
 */
@RestController
@RequestMapping("/internal/llm")
@RequiredArgsConstructor
public class InternalLlmController {

    private final LlmClient llmClient;

    @PostMapping("/chat")
    public LlmPort.ChatResponse chat(@RequestBody LlmPort.ChatRequest request) {
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new BizException(BizException.BIZ_ERROR, "messages 不能为空");
        }
        List<LlmClient.Message> messages = request.getMessages().stream()
                .filter(m -> m != null && StringUtils.hasText(m.getRole()))
                .map(m -> new LlmClient.Message(m.getRole(), m.getContent() == null ? "" : m.getContent()))
                .toList();
        if (messages.isEmpty()) {
            throw new BizException(BizException.BIZ_ERROR, "messages 不能为空");
        }
        String content = llmClient.chat(messages, request.getTemperature(), request.getMaxTokens());
        return new LlmPort.ChatResponse(content);
    }
}
