package cn.diaojiahong.myyoutube.chat.controller;

import cn.diaojiahong.myyoutube.chat.mongo.dto.ChatMessageDTO;
import cn.diaojiahong.myyoutube.chat.mongo.dto.ChatMessageWithdrawDTO;
import cn.diaojiahong.myyoutube.chat.mongo.dto.MessageListPageQuery;
import cn.diaojiahong.myyoutube.chat.mongo.dto.ack.ChatMessageAckDTO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.MessagePageVO;
import cn.diaojiahong.myyoutube.chat.service.impl.ChatMessageService;
import cn.diaojiahong.myyoutube.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/chat/message")
@RequiredArgsConstructor
@Tag(name = "聊天模块")
public class ChatMessageController {

    private final ChatMessageService chatMessageService;

    /**
     * 发送消息
     * @param chatMessageDTO
     * @return
     */
    @Operation(summary = "发送消息")
    @PostMapping("/send")
    public Result<ChatMessageAckDTO> send(@Valid @RequestBody ChatMessageDTO chatMessageDTO) {
        return chatMessageService.send(chatMessageDTO);
    }

    /**
     * 撤回消息
     * @param chatMessageWithdrawDTO
     * @return
     */
    @Operation(summary = "撤回消息")
    @PostMapping("/withdraw")
    public Result<ChatMessageAckDTO> withdraw(@Valid @RequestBody ChatMessageWithdrawDTO chatMessageWithdrawDTO) {
        return chatMessageService.withdraw(chatMessageWithdrawDTO);
    }

    /**
     * 分页查询历史消息
     * @param conversationId
     * @param messageListPageQuery
     * @return
     */
    @Operation(summary = "分页查询历史消息")
    @GetMapping("/list/{conversationId}")
    public Result<MessagePageVO> getMessageList(@PathVariable String conversationId, @Valid @ModelAttribute MessageListPageQuery messageListPageQuery) {
        return chatMessageService.getMessageList(conversationId, messageListPageQuery);
    }

    /**
     * 重连查询增量消息(同步消息)
     * @param conversationId
     * @param messageListPageQuery
     * @return
     */
    @Operation(summary = "重连查询增量消息(同步消息)")
    @GetMapping("/sync/{conversationId}")
    public Result<MessagePageVO> syncMessageList(@PathVariable String conversationId, @Valid @ModelAttribute MessageListPageQuery messageListPageQuery) {
        return chatMessageService.syncMessageList(conversationId, messageListPageQuery);
    }

    /**
     * 会话消息已读(私聊才有)
     * @param conversationId
     * @return
     */
    @Operation(summary = "会话消息已读")
    @PostMapping("/read/{conversationId}")
    public Result<Void> read(@PathVariable String conversationId, @RequestParam Long lastReadSeq) {
        return chatMessageService.read(conversationId, lastReadSeq);
    }

}

