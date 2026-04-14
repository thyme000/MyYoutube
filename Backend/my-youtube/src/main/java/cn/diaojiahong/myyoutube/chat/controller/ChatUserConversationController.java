package cn.diaojiahong.myyoutube.chat.controller;

import cn.diaojiahong.myyoutube.chat.mongo.vo.UserConversationVO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.ValidUserConversationVO;
import cn.diaojiahong.myyoutube.chat.service.impl.ChatUserConversationService;
import cn.diaojiahong.myyoutube.common.page.PageResult;
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

    private final ChatUserConversationService chatUserConversationService;

    /**
     * 开启或关闭免打扰
     * @param conversationId 聊天会话ID
     * @param mute 是否开启免打扰模式
     */
    @Operation(summary = "开启或关闭免打扰")
    @PutMapping("/{conversationId}/mute")
    public Result<Void> setMute(@PathVariable String conversationId, @RequestParam boolean mute) {
        return chatUserConversationService.setMute(conversationId, mute);
    }

    /**
     * 对某会话置顶或取消置顶
     * @param conversationId 聊天会话ID
     * @param pin 是否置顶
     */
    @Operation(summary = "对某会话置顶或取消置顶")
    @PutMapping("/{conversationId}/pin")
    public Result<Void> setPin(@PathVariable String conversationId, @RequestParam boolean pin) {
        return chatUserConversationService.setPin(conversationId, pin);
    }

    /**
     * 获取用户的所有聊天会话
     * @return
     */
    @Operation(summary = "获取用户所有聊天会话")
    @GetMapping("/list")
    public Result<PageResult<UserConversationVO>> getPageConversations(@RequestParam Long page, @RequestParam Long pageSize) {
        return chatUserConversationService.getPageConversations(page, pageSize);
    }

    /**
     * 查询用户所有未读的消息
     * @return
     */
    @Operation(summary = "查询用户所有未读的消息")
    @GetMapping("/unread")
    public Result<Long> getUnreadMessages() {
        return chatUserConversationService.getUnreadMessages();
    }

    /**
     * 读取群公告
     * @param conversationId
     * @return
     */
    @Operation(summary = "读取群公告")
    @PutMapping("/{conversationId}/read/announcement")
    public Result<Void> readAnnouncement(@PathVariable String conversationId) {
        return chatUserConversationService.readAnnouncement(conversationId);
    }

    /**
     * 获取用户合法的聊天会话列表
     * @param page
     * @param pageSize
     * @return
     */
    @Operation(summary = "获取用户合法的聊天会话列表")
    @GetMapping("/valid")
    public Result<PageResult<ValidUserConversationVO>> getValidConversations(@RequestParam Long page, @RequestParam Long pageSize) {
        return chatUserConversationService.getValidConversations(page, pageSize);
    }
}
