package cn.diaojiahong.myyoutube.chat.controller;

import cn.diaojiahong.myyoutube.chat.mongo.dto.CreateConversationDTO;
import cn.diaojiahong.myyoutube.chat.mongo.dto.PublishAnnouncementDTO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.AnnouncementPublishVO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.ConversationMembersVO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.ConversationVO;
import cn.diaojiahong.myyoutube.chat.service.impl.ChatConversationService;
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

    private final ChatConversationService chatConversationService;

    /**
     * 用户创建会话
     * @param createConversationDTO
     * @return
     */
    @Operation(summary = "创建会话")
    @PostMapping("/create")
    public Result<ConversationVO> createConversation(@RequestBody CreateConversationDTO createConversationDTO) {
        return chatConversationService.createConversation(createConversationDTO);
    }

    /**
     * 修改群会话头像(只有创建人有权限修改)
     * @param conversationId 会话id
     * @param avatar 新头像
     * @return
     */
    @Operation(summary = "修改群头像")
    @PutMapping("/avatar/{conversationId}")
    public Result<String> updateConversationAvatar(@PathVariable String conversationId, @RequestParam MultipartFile avatar) {
        return chatConversationService.updateConversationAvatar(conversationId, avatar);
    }

    /**
     * 修改群话名称(只有创建人有权限修改)
     * @param conversationId 会话id
     * @param name 群名称
     * @return
     */
    @Operation(summary = "修改群名称")
    @PutMapping("/name/{conversationId}")
    public Result<String> updateConversationName(@PathVariable String conversationId, @RequestParam String name) {
        return chatConversationService.updateConversationName(conversationId, name);
    }

    /**
     * 删除会话
     * @param conversationId 会话id
     * @return
     */
    @Operation(summary = "删除会话")
    @DeleteMapping("/delete/{conversationId}")
    public Result<String> deleteConversation(@PathVariable String conversationId) {
        return chatConversationService.deleteConversation(conversationId);
    }

    /**
     * 移除会话成员
     * @param conversationId 会话id
     * @param userIds 用户id
     * @return
     */
    @Operation(summary = "移除会话成员")
    @DeleteMapping("/remove/member/{conversationId}")
    public Result<Void> removeConversationMember(@PathVariable String conversationId, @RequestParam List<Long> userIds) {
        return chatConversationService.removeConversationMember(conversationId, userIds);
    }

    /**
     * 邀请互关用户加入此群
     * @param conversationId 会话id
     * @param userId 用户id
     * @return
     */
    @Operation(summary = "邀请互关用户加入群")
    @PutMapping("/invite/{conversationId}/{userId}")
    public Result<Void> inviteConversationMember(@PathVariable String conversationId, @PathVariable Long userId) {
        return chatConversationService.inviteConversationMember(conversationId, userId);
    }

    /**
     * 查询群成员列表
     * @param conversationId 会话id
     * @return
     */
    @Operation(summary = "查询群成员列表")
    @GetMapping("/member/list/{conversationId}")
    public Result<List<ConversationMembersVO>> getConversationMembers(@PathVariable String conversationId) {
        return chatConversationService.getConversationMembers(conversationId);
    }

    /**
     * 开启全员禁言(只有创建人才有权限)
     * @param conversationId 会话id
     * @return
     */
    @Operation(summary = "开启或关闭全员禁言")
    @PutMapping("/ban/{conversationId}")
    public Result<Void> banConversation(@PathVariable String conversationId, @RequestParam Boolean banned) {
        return chatConversationService.banConversation(conversationId, banned);
    }

    /**
     * 发布/更新群公告（仅群主）；Body：{ "text": "..." }
     */
    @Operation(summary = "发布群公告")
    @PutMapping("/announcement/{conversationId}")
    public Result<AnnouncementPublishVO> publishAnnouncement(
            @PathVariable String conversationId,
            @Valid @RequestBody PublishAnnouncementDTO body) {
        return chatConversationService.publishAnnouncement(conversationId, body.getText());
    }

    /**
     * 查询群公告
     * @param conversationId 会话id
     * @return
     */
    @Operation(summary = "查询群公告")
    @GetMapping("/announcement/{conversationId}")
    public Result<AnnouncementPublishVO> getAnnouncement(@PathVariable String conversationId) {
        return chatConversationService.getAnnouncement(conversationId);
    }
}
