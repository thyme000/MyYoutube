package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatUserConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.vo.UserConversationVO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.ValidUserConversationVO;
import cn.diaojiahong.myyoutube.chat.service.BaseMongoService;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatUserConversationService extends BaseMongoService<ChatUserConversationDoc> {

    private static final Long STORAGE_EXPIRE_TIME = 3600 * 10L;
    private final S3StorageUtil storageUtil;
    private final UserPort userPort;

    public ChatUserConversationService(MongoTemplate mongoTemplate,
                                       S3StorageUtil storageUtil,
                                       UserPort userPort) {
        super(mongoTemplate, ChatUserConversationDoc.class);
        this.storageUtil = storageUtil;
        this.userPort = userPort;
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.parseLong(userId);
    }

    public Result<Void> setMute(String conversationId, boolean mute) {
        Criteria c = Criteria.where("conversationId").is(conversationId).and("userId").is(getUserId());
        Update u = new Update().set("mute", mute);
        mongoTemplate.updateFirst(Query.query(c), u, ChatUserConversationDoc.class);
        return Result.success();
    }

    public Result<Void> setPin(String conversationId, boolean pin) {
        Criteria c = Criteria.where("conversationId").is(conversationId).and("userId").is(getUserId());
        Update u = new Update().set("pin", pin);
        mongoTemplate.updateFirst(Query.query(c), u, ChatUserConversationDoc.class);
        return Result.success();
    }

    public Result<PageResult<UserConversationVO>> getPageConversations(Long page, Long pageSize) {
        // 置顶优先,其次按最后活跃时间来排序
        Query query = Query.query(
                Criteria.where("userId").is(getUserId()).and("deleted").is(false));
        query.with(Sort.by(
                Sort.Order.desc("pin"),
                Sort.Order.desc("lastPullAt")
        ));
        Pageable pageable = PageRequest.of(
                page.intValue() - 1,
                pageSize.intValue());
        Page<ChatUserConversationDoc> p = this.page(query, pageable);
        List<ChatUserConversationDoc> content = p.getContent();
        if (content.isEmpty()) {
            return Result.success(PageResult.of(0, 0, List.of()));
        }
        List<String> list = content.stream().map(ChatUserConversationDoc::getConversationId).toList();
        Query q = Query.query(Criteria.where("id").in(list));
        List<ChatConversationDoc> chatConversationDocList = mongoTemplate.find(q, ChatConversationDoc.class);
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
                        conversationDoc.getMemberIds().remove(getUserId());
                        UserSummary user = userPort.getUser(conversationDoc.getMemberIds().get(0));
                        vo.setConversationAvatar(storageUtil.signGetUrl(user.getAvatarUrl(), STORAGE_EXPIRE_TIME));
                        vo.setConversationName(user.getNickname());
                    } else {
                        // 需要展示的是群聊名称
                        vo.setConversationAvatar(storageUtil.signGetUrl(conversationDoc.getAvatar(), STORAGE_EXPIRE_TIME));
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
        return Result.success(PageResult.of(p.getTotalElements(), p.getTotalPages(), voList));
    }

    public Result<Long> getUnreadMessages() {
        Long userId = getUserId();
        // 统计用户所有会话的未读消息数总和
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userId").is(userId).and("deleted").is(false)),
                Aggregation.group().sum("unreadCount").as("totalUnread")
        );
        // 使用 Document 接收聚合结果
        AggregationResults<Document> results = mongoTemplate.aggregate(
                aggregation, "chat_user_conversation", Document.class
        );
        // 获取统计结果（$sum 在 BSON 中常为 Long，不能用 getInteger 强转）
        Document result = results.getUniqueMappedResult();
        if (result == null) {
            return Result.success(0L);
        }
        Object raw = result.get("totalUnread");
        Long total = raw instanceof Number ? ((Number) raw).longValue() : 0L;
        return Result.success(total);
    }

    public Result<Void> readAnnouncement(String conversationId) {
        Long userId = getUserId();
        Criteria c = Criteria.where("id").is(conversationId);
        Query query = Query.query(c);
        ChatConversationDoc chatConversationDoc = mongoTemplate.findOne(query, ChatConversationDoc.class);
        if (chatConversationDoc == null) {
            return Result.fail("会话不存在");
        }
        // 读取群公告
        if (chatConversationDoc.getAnnouncement() == null) {
            return Result.success(null);
        }
        if (!chatConversationDoc.getMemberIds().contains(userId)) {
            return Result.fail("用户不是群成员");
        }
        c = Criteria.where("conversationId").is(conversationId).and("userId").is(userId);
        Update update = new Update().set("lastSeenAnnouncementVersion", chatConversationDoc.getAnnouncementVersion());
        mongoTemplate.updateFirst(Query.query(c), update, ChatUserConversationDoc.class);
        return Result.success();
    }

    public Result<PageResult<ValidUserConversationVO>> getValidConversations(Long page, Long pageSize) {
        // 置顶优先,其次按最后活跃时间来排序
        Long userId = getUserId();
        Query query = Query.query(
                Criteria.where("userId").is(userId)
                        .and("deleted").is(false)
                        .and("banned").is(false));
        query.with(Sort.by(
                Sort.Order.desc("pin"),
                Sort.Order.desc("lastPullAt")
        ));
        Pageable pageable = PageRequest.of(
                page.intValue() - 1,
                pageSize.intValue());
        Page<ChatUserConversationDoc> p = this.page(query, pageable);
        List<ChatUserConversationDoc> content = p.getContent();
        if (content.isEmpty()) {
            return Result.success(PageResult.of(0, 0, List.of()));
        }
        List<String> list = new java.util.ArrayList<>(content.stream()
                .map(ChatUserConversationDoc::getConversationId).toList());
        // 排除掉全员禁言的群聊,但群主不排除
        Criteria c = Criteria.where("id").in(list).and("banned").is(true).and("createdBy").ne(userId);
        Query qu = Query.query(c);
        List<ChatConversationDoc> removeConversation = mongoTemplate.find(qu, ChatConversationDoc.class);
        List<String> removeList = removeConversation.stream().map(ChatConversationDoc::getId).toList();
        list.removeAll(removeList);
        // 查询合法的会话文档
        qu = Query.query(Criteria.where("id").in(list));
        List<ChatConversationDoc> chatConversationDocList = mongoTemplate.find(qu, ChatConversationDoc.class);
        // 转map
        Map<String, ChatConversationDoc> collect = chatConversationDocList.stream()
                .collect(Collectors.toMap(ChatConversationDoc::getId, Function.identity()));
        // 转vo
        List<ValidUserConversationVO> voList = content.stream()
                .map(v -> {
                    ValidUserConversationVO vo = new ValidUserConversationVO();
                    vo.setConversationId(v.getConversationId());
                    vo.setConversationName(collect.get(v.getConversationId()).getName());
                    vo.setConversationType(collect.get(v.getConversationId()).getType());
                    vo.setConversationAvatar(storageUtil.signGetUrl(collect.get(v.getConversationId()).getAvatar(), 60 * 5));
                    return vo;
                })
                .toList();
        return Result.success(PageResult.of(p.getTotalElements(), p.getTotalPages(), voList));
    }
}
