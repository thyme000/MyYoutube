package cn.diaojiahong.myyoutube.chat.controller;

import cn.diaojiahong.myyoutube.chat.domain.vo.UserConversationVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.ValidUserConversationVO;
import cn.diaojiahong.myyoutube.chat.service.impl.ConversationMemberService;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.page.CursorPageQuery;
import cn.diaojiahong.myyoutube.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat/user/conversation")
@RequiredArgsConstructor
@Tag(name = "聊天模块")
public class ChatUserConversationController {

    private final ConversationMemberService chatUserConversationService;

    /**
     * 开启或关闭免打扰
     * @param conversationId 聊天会话ID
     * @param mute 是否开启免打扰模式
     */
    @Operation(summary = "开启或关闭免打扰")
    @PutMapping("/{conversationId}/mute")
    public Result<Void> setMute(@PathVariable String conversationId, @RequestParam boolean mute) {
        chatUserConversationService.setMute(conversationId, mute);
        return Result.success();
    }

    /**
     * 对某会话置顶或取消置顶
     * @param conversationId 聊天会话ID
     * @param pin 是否置顶
     */
    @Operation(summary = "对某会话置顶或取消置顶")
    @PutMapping("/{conversationId}/pin")
    public Result<Void> setPin(@PathVariable String conversationId, @RequestParam boolean pin) {
        chatUserConversationService.setPin(conversationId, pin);
        return Result.success();
    }

    /**
     * 获取用户的聊天会话。每页条数由服务端决定，续查只回传 cursor。
     */
    @Operation(summary = "获取用户聊天会话",
            description = "返回 items、nextCursor、hasMore。hasMore 为真时带回 nextCursor。")
    @GetMapping("/list")
    public Result<CursorPage<UserConversationVO, String>> list(@ModelAttribute CursorPageQuery query) {
        return Result.success(chatUserConversationService.listInbox(query.getCursor()));
    }

    @Operation(summary = "查询与指定用户的私聊",
            description = "自己没有成员行的空私聊(还没打开过)返回空, 不创建会话。")
    @GetMapping("/private/{peerUserId}")
    public Result<UserConversationVO> findPrivate(@PathVariable Long peerUserId) {
        return Result.success(chatUserConversationService.findPrivateInbox(peerUserId));
    }

    @Operation(summary = "打开或复用私聊",
            description = "按排序后的双方ID查找或创建唯一私聊; 已有会话就恢复自己的成员行返回会话, 空会话对方不可见。")
    @PutMapping("/private/{peerUserId}")
    public Result<UserConversationVO> openPrivate(@PathVariable Long peerUserId) {
        return Result.success(chatUserConversationService.openPrivate(peerUserId));
    }

    /**
     * 查询用户所有未读的消息
     * @return
     */
    @Operation(summary = "查询用户所有未读的消息")
    @GetMapping("/unread")
    public Result<Long> getUnreadMessages() {
        return Result.success(chatUserConversationService.getUnreadMessages());
    }

    /**
     * 读取群公告
     * @param conversationId
     * @return
     */
    @Operation(summary = "读取群公告")
    @PutMapping("/{conversationId}/read/announcement")
    public Result<Void> readAnnouncement(@PathVariable String conversationId) {
        chatUserConversationService.readAnnouncement(conversationId);
        return Result.success();
    }

    /**
     * 获取还能发消息的会话。全员禁言且自己不是群主的群，以及自己被禁言的会话，不出现在结果里。
     */
    @Operation(summary = "获取用户可发送的聊天会话",
            description = "返回 items、nextCursor、hasMore。hasMore 为真时带回 nextCursor。")
    @GetMapping("/valid")
    public Result<CursorPage<ValidUserConversationVO, String>> listSendable(@ModelAttribute CursorPageQuery query) {
        return Result.success(chatUserConversationService.listSendable(query.getCursor()));
    }
}
