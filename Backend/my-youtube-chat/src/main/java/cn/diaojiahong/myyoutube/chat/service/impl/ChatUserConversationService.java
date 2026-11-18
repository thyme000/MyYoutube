package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.chat.mapper.ChatConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ChatUserConversationMapper;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatUserConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.vo.UserConversationVO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.ValidUserConversationVO;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatUserConversationService {

    private static final Long STORAGE_EXPIRE_TIME = 3600 * 10L;

    private final ChatUserConversationMapper userConversationMapper;
    private final ChatConversationMapper conversationMapper;
    private final S3StorageUtil storageUtil;
    private final UserPort userPort;

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }

        return Long.parseLong(userId);
    }

    public void setMute(String conversationId, boolean mute) {
        userConversationMapper.update(null, new LambdaUpdateWrapper<ChatUserConversationDoc>()
                .eq(ChatUserConversationDoc::getConversationId, conversationId)
                .eq(ChatUserConversationDoc::getUserId, getUserId())
                .set(ChatUserConversationDoc::getMute, mute));
    }

    public void setPin(String conversationId, boolean pin) {
        userConversationMapper.update(null, new LambdaUpdateWrapper<ChatUserConversationDoc>()
                .eq(ChatUserConversationDoc::getConversationId, conversationId)
                .eq(ChatUserConversationDoc::getUserId, getUserId())
                .set(ChatUserConversationDoc::getPin, pin));
    }

    public PageResult<UserConversationVO> getPageConversations(Long page, Long pageSize) {
        // 置顶优先,其次按最后活跃时间来排序
        Long userId = getUserId();
        Page<ChatUserConversationDoc> p = userConversationMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<ChatUserConversationDoc>()
                        .eq(ChatUserConversationDoc::getUserId, userId)
                        .eq(ChatUserConversationDoc::getDeleted, false)
                        .orderByDesc(ChatUserConversationDoc::getPin)
                        .orderByDesc(ChatUserConversationDoc::getLastPullAt));
        List<ChatUserConversationDoc> content = p.getRecords();
        if (content.isEmpty()) {
            return PageResult.of(0, 0, List.of());
        }

        List<String> list = content.stream().map(ChatUserConversationDoc::getConversationId).toList();
        List<ChatConversationDoc> chatConversationDocList = conversationMapper.selectList(
                new LambdaQueryWrapper<ChatConversationDoc>().in(ChatConversationDoc::getId, list));
        // 转map
        Map<String, ChatConversationDoc> collect = chatConversationDocList.stream()
                .collect(Collectors.toMap(ChatConversationDoc::getId, Function.identity()));
        // 转vo
        List<UserConversationVO> voList = content.stream()
                .map(c -> {
                    ChatConversationDoc conversationDoc = collect.get(c.getConversationId());
                    boolean isSingle = conversationDoc.getType() == ConversationTypeEnum.SINGLE;
                    UserConversationVO vo = new UserConversationVO();
                    vo.setConversationId(c.getConversationId());
                    if (isSingle) {
                        // 需要展示的是对方的头像和昵称
                        conversationDoc.getMemberIds().remove(userId);
                        UserSummary user = userPort.getUser(conversationDoc.getMemberIds().get(0));
                        vo.setConversationAvatar(storageUtil.signGetUrl(user.getAvatarUrl(), STORAGE_EXPIRE_TIME));
                        vo.setConversationName(user.getNickname());
                    } else {
                        // 需要展示的是群聊名称
                        vo.setConversationAvatar(storageUtil.signGetUrl(
                                conversationDoc.getAvatar(),
                                STORAGE_EXPIRE_TIME));
                        vo.setConversationName(conversationDoc.getName() == null ? conversationDoc.getId() : conversationDoc.getName());
                    }
                    vo.setConversationType(conversationDoc.getType());
                    vo.setLastMessageTime(conversationDoc.getLastMessageAt());
                    vo.setLastMessageContent(conversationDoc.getIsLastMessageRecall() ? "撤回了一条消息" : conversationDoc.getLastMessagePreview());
                    vo.setLastMessageSenderNickname(isSingle ? null : conversationDoc.getLastMessageSenderNickname());
                    vo.setIsLastMessageRecall(conversationDoc.getIsLastMessageRecall());
                    vo.setBanned(c.getBanned());
                    vo.setConversationBanned(conversationDoc.getBanned());
                    vo.setPin(c.getPin());
                    vo.setMute(c.getMute());
                    vo.setLastReadSeq(c.getLastReadSeq());
                    vo.setUnreadCount(c.getUnreadCount());
                    vo.setCreatedBy(conversationDoc.getCreatedBy());
                    vo.setLastSeenAnnouncementVersion(c.getLastSeenAnnouncementVersion());
                    vo.setLastAnnouncementVersion(conversationDoc.getAnnouncementVersion());
                    return vo;
                })
                .toList();
        return PageResult.of(p.getTotal(), p.getPages(), voList);
    }

    public Long getUnreadMessages() {
        return userConversationMapper.sumUnread(getUserId());
    }

    public void readAnnouncement(String conversationId) {
        Long userId = getUserId();
        ChatConversationDoc chatConversationDoc = conversationMapper.selectById(conversationId);
        if (chatConversationDoc == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }

        // 读取群公告
        if (chatConversationDoc.getAnnouncement() == null) {
            return;
        }

        if (!chatConversationDoc.getMemberIds().contains(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "用户不是群成员");
        }

        userConversationMapper.update(null, new LambdaUpdateWrapper<ChatUserConversationDoc>()
                .eq(ChatUserConversationDoc::getConversationId, conversationId)
                .eq(ChatUserConversationDoc::getUserId, userId)
                .set(ChatUserConversationDoc::getLastSeenAnnouncementVersion, chatConversationDoc.getAnnouncementVersion()));
    }

    public PageResult<ValidUserConversationVO> getValidConversations(Long page, Long pageSize) {
        // 置顶优先,其次按最后活跃时间来排序
        Long userId = getUserId();
        Page<ChatUserConversationDoc> p = userConversationMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<ChatUserConversationDoc>()
                        .eq(ChatUserConversationDoc::getUserId, userId)
                        .eq(ChatUserConversationDoc::getDeleted, false)
                        .eq(ChatUserConversationDoc::getBanned, false)
                        .orderByDesc(ChatUserConversationDoc::getPin)
                        .orderByDesc(ChatUserConversationDoc::getLastPullAt));
        List<ChatUserConversationDoc> content = p.getRecords();
        if (content.isEmpty()) {
            return PageResult.of(0, 0, List.of());
        }

        List<String> list = new ArrayList<>(content.stream()
                .map(ChatUserConversationDoc::getConversationId).toList());
        // 排除掉全员禁言的群聊,但群主不排除
        List<ChatConversationDoc> removeConversation = conversationMapper.selectList(
                new LambdaQueryWrapper<ChatConversationDoc>()
                        .in(ChatConversationDoc::getId, list)
                        .eq(ChatConversationDoc::getBanned, true)
                        .ne(ChatConversationDoc::getCreatedBy, userId));
        List<String> removeList = removeConversation.stream().map(ChatConversationDoc::getId).toList();
        list.removeAll(removeList);
        // 查询合法的会话文档
        List<ChatConversationDoc> chatConversationDocList = list.isEmpty()
                ? List.of()
                : conversationMapper.selectList(new LambdaQueryWrapper<ChatConversationDoc>()
                        .in(ChatConversationDoc::getId, list));
        // 转map
        Map<String, ChatConversationDoc> collect = chatConversationDocList.stream()
                .collect(Collectors.toMap(ChatConversationDoc::getId, Function.identity()));
        // 转vo
        List<ValidUserConversationVO> voList = content.stream()
                .filter(v -> collect.containsKey(v.getConversationId()))
                .map(v -> {
                    ValidUserConversationVO vo = new ValidUserConversationVO();
                    vo.setConversationId(v.getConversationId());
                    vo.setConversationName(collect.get(v.getConversationId()).getName());
                    vo.setConversationType(collect.get(v.getConversationId()).getType());
                    vo.setConversationAvatar(storageUtil.signGetUrl(
                            collect.get(v.getConversationId()).getAvatar(),
                            60 * 5));
                    return vo;
                })
                .toList();
        return PageResult.of(p.getTotal(), p.getPages(), voList);
    }
}
