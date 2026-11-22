package cn.diaojiahong.myyoutube.chat.controller;

import cn.diaojiahong.myyoutube.chat.domain.dto.ChatMessageDTO;
import cn.diaojiahong.myyoutube.chat.domain.dto.ChatMessageWithdrawDTO;
import cn.diaojiahong.myyoutube.chat.domain.dto.ack.ChatMessageAckDTO;
import cn.diaojiahong.myyoutube.chat.domain.enums.MessageDirectionEnum;
import cn.diaojiahong.myyoutube.chat.domain.vo.MessageItemVO;
import cn.diaojiahong.myyoutube.chat.service.impl.ChatMessageQueryService;
import cn.diaojiahong.myyoutube.chat.service.impl.ChatMessageService;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
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
    private final ChatMessageQueryService chatMessageQueryService;

    @Operation(summary = "发送消息", description = "conversationId 与 peerUserId 二选一。按用户发送时复用已有私聊，首次发送成功后返回新会话 ID。")
    @PostMapping("/send")
    public Result<ChatMessageAckDTO> send(@Valid @RequestBody ChatMessageDTO chatMessageDTO) {
        return Result.success(chatMessageService.send(chatMessageDTO));
    }

    @Operation(summary = "撤回消息")
    @PostMapping("/withdraw")
    public Result<ChatMessageAckDTO> withdraw(@Valid @RequestBody ChatMessageWithdrawDTO chatMessageWithdrawDTO) {
        return Result.success(chatMessageService.withdraw(chatMessageWithdrawDTO));
    }

    @Operation(summary = "双向分页查询会话消息",
            description = "direction=BEFORE(默认)向更旧回溯, 省略 cursor 取最新一页; direction=AFTER 向更新推进, 必须传 cursor。"
                    + "续查把上一页的 nextCursor 作为 cursor 原样回传(数字); 每页条数由服务端决定。")
    @GetMapping("/list/{conversationId}")
    public Result<CursorPage<MessageItemVO, Long>> getMessageList(
            @PathVariable String conversationId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "BEFORE") MessageDirectionEnum direction) {
        return Result.success(chatMessageQueryService.getMessageList(conversationId, cursor, direction));
    }

    @Operation(summary = "清空自己的聊天记录")
    @DeleteMapping("/history/{conversationId}")
    public Result<Void> clearHistory(@PathVariable String conversationId) {
        chatMessageService.clearHistory(conversationId);
        return Result.success();
    }

    @Operation(summary = "会话消息已读")
    @PostMapping("/read/{conversationId}")
    public Result<Void> read(@PathVariable String conversationId, @RequestParam Long lastReadSeq) {
        chatMessageService.read(conversationId, lastReadSeq);
        return Result.success();
    }

    /**
     * 游标查找聊天记录: 首次不传 cursor, 续查传上一页的 nextCursor; 不返回总数。
     * @param conversationId
     * @param keyword
     * @param seq 上一页的 nextCursor，首页不传
     * @return
     */
    @Operation(summary = "查找聊天记录",
            description = "游标分页: 续查把上一页的 nextCursor 作为 seq 原样回传(数字), hasMore 为假时到底; 每页条数由服务端决定。")
    @GetMapping("/find/record/{conversationId}")
    public Result<CursorPage<MessageItemVO, Long>> findRecord(
            @PathVariable String conversationId,
            @RequestParam String keyword,
            @RequestParam(required = false) Long seq) {
        return Result.success(chatMessageQueryService.findRecord(conversationId, keyword, seq));
    }

}
