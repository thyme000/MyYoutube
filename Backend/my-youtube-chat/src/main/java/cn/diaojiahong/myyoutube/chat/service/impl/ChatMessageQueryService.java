package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.mapper.MessageMapper;
import cn.diaojiahong.myyoutube.chat.domain.enums.MessageDirectionEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Message;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import cn.diaojiahong.myyoutube.chat.domain.vo.MessageItemVO;
import cn.diaojiahong.myyoutube.chat.service.strategy.ChatContentTypeStrategy;
import cn.diaojiahong.myyoutube.chat.service.strategy.ChatContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummaries;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ChatMessageQueryService {

    private static final Long STORAGE_EXPIRE = 3600L * 10;
    // 每页条数由服务端决定, 与通用游标约定一致, 调用方只回传游标
    private static final int MESSAGE_PAGE_SIZE = 20;

    private final MessageMapper messageMapper;
    private final ChatMessageService chatMessageService;
    private final UserPort userPort;
    private final S3StorageUtil storageUtil;
    private final ChatContentTypeStrategyRegistry contentTypeStrategies;

    public CursorPage<MessageItemVO, Long> getMessageList(String conversationId, Long cursor, MessageDirectionEnum direction) {
        chatMessageService.getConversation(conversationId);
        // 成员校验只用于鉴权; 发送人信息按本页消息的 senderId 映射, 不依赖其是否仍在群里
        ConversationMember member = chatMessageService.requireActiveMember(conversationId);
        long visibleAfterSeq = visibleAfter(member);
        Page<Message> page = pageByDirection(conversationId, visibleAfterSeq, cursor, direction);
        return toMessageCursorPage(page.getRecords());
    }

    public CursorPage<MessageItemVO, Long> findRecord(
            String conversationId,
            String keyword,
            Long seq) {
        chatMessageService.getConversation(conversationId);
        ConversationMember member = chatMessageService.requireActiveMember(conversationId);

        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return new CursorPage<>(List.of(), null, false);
        }

        String pattern = "%" + escapeLike(kw) + "%";
        String fieldLike = "JSON_UNQUOTE(JSON_EXTRACT(content, '$.%s')) LIKE {0} ESCAPE '\\\\'";
        LambdaQueryWrapper<Message> query = new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .gt(Message::getSeq, visibleAfter(member))
                .lt(seq != null, Message::getSeq, seq)
                .and(recalled -> recalled.isNull(Message::getRecalled)
                        .or()
                        .eq(Message::getRecalled, false))
                .and(text -> text.apply(fieldLike.formatted("text"), pattern)
                        .or()
                        .apply(fieldLike.formatted("fileName"), pattern))
                .orderByDesc(Message::getSeq);
        // LIKE 条件用不上索引, fetchPage 关计数就是为了让这条查询只扫一遍
        Page<Message> page = messageMapper.selectPage(fetchPage(), query);
        return toMessageCursorPage(page.getRecords());
    }

    /** visibleAfterSeq 是清空历史后的可见下限，两个方向都不回放它之前的消息。 */
    private Page<Message> pageByDirection(
            String conversationId,
            long visibleAfterSeq,
            Long cursor,
            MessageDirectionEnum direction) {
        LambdaQueryWrapper<Message> query = new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .gt(Message::getSeq, visibleAfterSeq);
        if (direction == MessageDirectionEnum.AFTER) {
            if (cursor == null) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "AFTER 方向必须携带 cursor");
            }
            query.gt(Message::getSeq, cursor).orderByAsc(Message::getSeq);
        } else {
            query.lt(cursor != null, Message::getSeq, cursor).orderByDesc(Message::getSeq);
        }
        return messageMapper.selectPage(fetchPage(), query);
    }

    // keyset 分页不需要总数: 关掉 COUNT, 多取一行只用于探测 hasMore
    private static Page<Message> fetchPage() {
        return new Page<>(1, CursorPage.fetchSize(MESSAGE_PAGE_SIZE), false);
    }

    // 把多取一行的结果切成本页+游标: 游标取本页最后一条, desc 向更旧与 asc 增量都成立。
    // 消息位置就是明牌的 seq, 与收件箱的复合键不同, 不做不透明编码, 数字原样回传即可续查
    private CursorPage<MessageItemVO, Long> toMessageCursorPage(List<Message> fetched) {
        CursorPage<Message, Long> sliced = CursorPage.slice(fetched, MESSAGE_PAGE_SIZE, Message::getSeq);
        return new CursorPage<>(toMessageItemsOf(sliced.items()), sliced.nextCursor());
    }

    private List<MessageItemVO> toMessageItemsOf(List<Message> docs) {
        List<UserSummary> senders = loadSendersOf(docs);
        Map<Long, String> authorNicknames = UserSummaries.toNicknameMap(senders);
        Map<Long, String> authorAvatarUrls = UserSummaries.toAvatarMap(senders);
        return toMessageItems(docs, authorNicknames, authorAvatarUrls);
    }

    private List<MessageItemVO> toMessageItems(
            List<Message> docs,
            Map<Long, String> authorNicknames,
            Map<Long, String> authorAvatarUrls) {
        return docs.stream().map(doc -> {
            MessageItemVO vo = new MessageItemVO();
            vo.setSenderId(doc.getSenderId());
            vo.setSenderNickName(authorNicknames.get(doc.getSenderId()));
            vo.setSenderAvatarUrl(storageUtil.signGetUrl(authorAvatarUrls.get(doc.getSenderId()), STORAGE_EXPIRE));
            vo.setContentType(doc.getContentType());
            boolean recalled = Boolean.TRUE.equals(doc.getRecalled());
            if (!recalled && doc.getContent() != null) {
                ChatContentTypeStrategy contentTypeStrategy = contentTypeStrategies.get(doc.getContentType());
                if (contentTypeStrategy != null) {
                    contentTypeStrategy.signUrls(doc.getContent(), STORAGE_EXPIRE);
                }
            }
            vo.setContent(recalled ? null : doc.getContent());
            vo.setMessageId(doc.getId());
            vo.setClientMsgId(doc.getClientMsgId());
            vo.setSeq(doc.getSeq());
            vo.setStatus(doc.getStatus());
            vo.setCreatedAt(doc.getCreatedAt());
            vo.setRecallType(doc.getRecallType());
            return vo;
        }).toList();
    }

    // 发送人按本页消息的 senderId 去重后查询: 退群/被移除的人不在成员名单里, 但其历史消息仍应显示昵称头像
    private List<UserSummary> loadSendersOf(List<Message> docs) {
        List<Long> senderIds = docs.stream()
                .map(Message::getSenderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (senderIds.isEmpty()) {
            return List.of();
        }
        return userPort.listByIds(senderIds);
    }

    /** 会话内搜索按字面量匹配，转义 LIKE 通配符。 */
    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static long visibleAfter(ConversationMember member) {
        if (member.getVisibleAfterSeq() == null) {
            return 0L;
        }
        return member.getVisibleAfterSeq();
    }
}
