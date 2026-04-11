package cn.diaojiahong.myyoutube.chat.controller;

import cn.diaojiahong.myyoutube.chat.mongo.dto.CreateConversationDTO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.ConversationMembersVO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.ConversationVO;
import cn.diaojiahong.myyoutube.chat.service.impl.ChatConversationService;
import cn.diaojiahong.myyoutube.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
     * @param userId 用户id
     * @return
     */
    @Operation(summary = "移除会话成员")
    @DeleteMapping("/remove/{conversationId}/{userId}")
    public Result<Void> removeConversationMember(@PathVariable String conversationId, @PathVariable String userId) {
        return chatConversationService.removeConversationMember(conversationId, userId);
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
}
