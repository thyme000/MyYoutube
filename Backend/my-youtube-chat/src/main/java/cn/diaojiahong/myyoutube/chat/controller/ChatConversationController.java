package cn.diaojiahong.myyoutube.chat.controller;

import cn.diaojiahong.myyoutube.chat.domain.dto.CreateConversationDTO;
import cn.diaojiahong.myyoutube.chat.domain.dto.PublishAnnouncementDTO;
import cn.diaojiahong.myyoutube.chat.domain.vo.AnnouncementPublishVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.ConversationInviteVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.ConversationMembersVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.ConversationVO;
import cn.diaojiahong.myyoutube.chat.service.impl.ConversationInviteService;
import cn.diaojiahong.myyoutube.chat.service.impl.ConversationService;
import cn.diaojiahong.myyoutube.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/chat/conversation")
@RequiredArgsConstructor
@Tag(name = "聊天模块")
public class ChatConversationController {

    private final ConversationService chatConversationService;
    private final ConversationInviteService conversationInviteService;

    @Operation(summary = "创建群聊")
    @PostMapping("/create")
    public Result<ConversationVO> createConversation(@Valid @RequestBody CreateConversationDTO createConversationDTO) {
        return Result.success(chatConversationService.createConversation(createConversationDTO));
    }

    @Operation(summary = "修改群头像")
    @PutMapping("/avatar/{conversationId}")
    public Result<String> updateConversationAvatar(
            @PathVariable String conversationId,
            @RequestParam MultipartFile avatar) {
        return Result.success(chatConversationService.updateConversationAvatar(conversationId, avatar));
    }

    @Operation(summary = "修改群名称")
    @PutMapping("/name/{conversationId}")
    public Result<String> updateConversationName(@PathVariable String conversationId, @RequestParam String name) {
        return Result.success(chatConversationService.updateConversationName(conversationId, name));
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/delete/{conversationId}")
    public Result<String> deleteConversation(@PathVariable String conversationId) {
        return Result.success(chatConversationService.deleteConversation(conversationId));
    }

    @Operation(summary = "移除会话成员")
    @DeleteMapping("/remove/member/{conversationId}")
    public Result<Void> removeConversationMember(
            @PathVariable String conversationId,
            @RequestParam List<Long> userIds) {
        chatConversationService.removeConversationMember(conversationId, userIds);
        return Result.success(null);
    }

    /**
     * 邀请互关用户。对方同意后才加入群。
     */
    @Operation(summary = "邀请互关用户")
    @PutMapping("/invite/{conversationId}/{userId}")
    public Result<Void> inviteConversationMember(@PathVariable String conversationId, @PathVariable Long userId) {
        conversationInviteService.inviteConversationMember(conversationId, userId);
        return Result.success(null);
    }

    /**
     * 我收到的群邀请，按邀请时间倒序。
     * @param status 可选筛选项 PENDING；省略时返回全部状态（PENDING/ACCEPTED/REJECTED）
     * @return
     */
    @Operation(summary = "我收到的群邀请")
    @GetMapping("/invites")
    public Result<List<ConversationInviteVO>> listInvites(@RequestParam(required = false) String status) {
        return Result.success(conversationInviteService.listInvites(status));
    }

    @Operation(summary = "同意群邀请")
    @PutMapping("/invite/{inviteId}/accept")
    public Result<Void> acceptInvite(@PathVariable String inviteId) {
        conversationInviteService.acceptInvite(inviteId);
        return Result.success(null);
    }

    @Operation(summary = "拒绝群邀请")
    @PutMapping("/invite/{inviteId}/reject")
    public Result<Void> rejectInvite(@PathVariable String inviteId) {
        conversationInviteService.rejectInvite(inviteId);
        return Result.success(null);
    }

    @Operation(summary = "查询群成员列表")
    @GetMapping("/member/list/{conversationId}")
    public Result<List<ConversationMembersVO>> getConversationMembers(@PathVariable String conversationId) {
        return Result.success(chatConversationService.getConversationMembers(conversationId));
    }

    @Operation(summary = "开启或关闭全员禁言")
    @PutMapping("/ban/{conversationId}")
    public Result<Void> banConversation(@PathVariable String conversationId, @RequestParam Boolean banned) {
        chatConversationService.banConversation(conversationId, banned);
        return Result.success(null);
    }

    @Operation(summary = "禁言或取消禁言会话成员")
    @PutMapping("/ban/{userId}/{conversationId}")
    public Result<Void> banConversationMember(
            @PathVariable Long userId,
            @PathVariable String conversationId,
            @RequestParam Boolean banned) {
        chatConversationService.banConversationMember(userId, conversationId, banned);
        return Result.success(null);
    }

    /**
     * 发布/更新群公告（仅群主）；Body：{ "text": "...", "clientReqId": "uuid" }
     */
    @Operation(summary = "发布群公告")
    @PutMapping("/announcement/{conversationId}")
    public Result<AnnouncementPublishVO> publishAnnouncement(
            @PathVariable String conversationId,
            @Valid @RequestBody PublishAnnouncementDTO body) {
        return Result.success(chatConversationService.publishAnnouncement(
                conversationId, body.getText(), body.getClientReqId()));
    }

    @Operation(summary = "查询群公告")
    @GetMapping("/announcement/{conversationId}")
    public Result<AnnouncementPublishVO> getAnnouncement(@PathVariable String conversationId) {
        return Result.success(chatConversationService.getAnnouncement(conversationId));
    }
}
