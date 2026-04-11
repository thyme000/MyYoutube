package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatUserConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.vo.UserConversationVO;
import cn.diaojiahong.myyoutube.chat.service.BaseMongoService;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
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

    private static final Long OSS_EXPIRE_TIME = 3600 * 2L;
    private final AliOssUtil aliOssUtil;

    public ChatUserConversationService(MongoTemplate mongoTemplate, Class<ChatUserConversationDoc> entityClass,
                                       AliOssUtil aliOssUtil) {
        super(mongoTemplate, entityClass);
        this.aliOssUtil = aliOssUtil;
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
        Query q = Query.query(Criteria.where("conversationId").in(list));
        List<ChatConversationDoc> chatConversationDocList = mongoTemplate.find(q, ChatConversationDoc.class);
        // 转map
        Map<String, ChatConversationDoc> collect = chatConversationDocList.stream()
                .collect(Collectors.toMap(ChatConversationDoc::getId, Function.identity()));
        // 转vo
        List<UserConversationVO> voList = content.stream()
                .map(c -> {
                    ChatConversationDoc conversationDoc = collect.get(c.getConversationId());
                    UserConversationVO vo = new UserConversationVO();
                    vo.setConversationId(c.getConversationId());
                    vo.setConversationName(conversationDoc.getName() == null ? conversationDoc.getId() : conversationDoc.getName());
                    vo.setConversationAvatar(aliOssUtil.signGetUrl(conversationDoc.getAvatar(), OSS_EXPIRE_TIME));
                    vo.setConversationType(conversationDoc.getType());
                    vo.setLastMessageTime(conversationDoc.getLastMessageAt());
                    vo.setLastMessageContent(conversationDoc.getIsLastMessageRecall() ? "撤回了一条消息" : conversationDoc.getLastMessagePreview());
                    vo.setLastMessageSenderNickname(conversationDoc.getType() == ConversationTypeEnum.SINGLE ?
                            null : conversationDoc.getLastMessageSenderNickname());
                    vo.setIsLastMessageRecall(conversationDoc.getIsLastMessageRecall());
                    vo.setBanned(c.getBanned());
                    vo.setPin(c.getPin());
                    vo.setMute(c.getMute());
                    vo.setLastReadSeq(c.getLastReadSeq());
                    vo.setUnreadCount(c.getUnreadCount());
                    return vo;
                })
                .toList();
        return Result.success(PageResult.of(p.getTotalElements(), p.getTotalPages(), voList));
    }

    public Result<Integer> getUnreadMessages() {
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
        // 获取统计结果
        Document result = results.getUniqueMappedResult();
        return Result.success(result != null ? result.getInteger("totalUnread", 0) : 0);
    }
}
