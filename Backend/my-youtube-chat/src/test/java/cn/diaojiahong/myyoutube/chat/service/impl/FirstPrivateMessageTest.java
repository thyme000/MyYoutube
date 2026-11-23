package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.chain.message.ConversationStateStep;
import cn.diaojiahong.myyoutube.chat.chain.message.ConversationStep;
import cn.diaojiahong.myyoutube.chat.chain.message.GlobalBannedStep;
import cn.diaojiahong.myyoutube.chat.chain.message.IdempotentStep;
import cn.diaojiahong.myyoutube.chat.chain.message.InterrelatedRateLimitStep;
import cn.diaojiahong.myyoutube.chat.chain.message.SenderBannedStep;
import cn.diaojiahong.myyoutube.chat.chain.message.TypeValidateStep;
import cn.diaojiahong.myyoutube.chat.domain.dto.ChatMessageDTO;
import cn.diaojiahong.myyoutube.chat.domain.dto.ack.ChatMessageAckDTO;
import cn.diaojiahong.myyoutube.chat.domain.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationStatus;
import cn.diaojiahong.myyoutube.chat.domain.enums.MessageDirectionEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.ChatContent;
import cn.diaojiahong.myyoutube.chat.domain.vo.ConversationUnreadItemVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.ConversationUnreadVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.MessageItemVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.UserConversationVO;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.chat.mapper.MessageMapper;
import cn.diaojiahong.myyoutube.chat.service.strategy.ChatContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.chat.service.strategy.TextChatContentStrategy;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.mq.message.ReadMessageMessage;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.content.api.ContentInternalPort;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FirstPrivateMessageTest {

    private final UserBlockPort blocks = mock(UserBlockPort.class);
    private final FollowPort follows = mock(FollowPort.class);
    private final UserPort users = mock(UserPort.class);
    private final RocketMQTemplate mq = mock(RocketMQTemplate.class);
    private JdbcTemplate jdbc;
    private ChatMessageService messages;
    private ChatMessageQueryService queries;
    private ConversationService conversations;
    private ConversationMemberService inbox;

    @BeforeEach
    void setup() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:first-private-message-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        String schema = Files.readString(Path.of("../../resources/schema.sql"));
        String chatTables = schema.substring(schema.indexOf("CREATE TABLE IF NOT EXISTS `conversation`"),
                schema.indexOf("-- ==================== search_db"));
        // H2 会把 JDBC 传入 JSON 列的字符串再次编码，测试库用 TEXT 保留原始正文。
        String h2Tables = chatTables.replace("`content` JSON", "`content` TEXT");
        new ResourceDatabasePopulator(new ByteArrayResource(h2Tables.getBytes(StandardCharsets.UTF_8)))
                .execute(source);
        jdbc = new JdbcTemplate(source);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(source);
        factoryBean.setMapperLocations(new ClassPathResource("mapper/ConversationMapper.xml"),
                new ClassPathResource("mapper/ConversationMemberMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(MessageMapper.class);
        SqlSessionTemplate session = new SqlSessionTemplate(factory);
        MessageMapper messageMapper = session.getMapper(MessageMapper.class);
        ConversationMapper conversationMapper = session.getMapper(ConversationMapper.class);
        ConversationMemberMapper memberMapper = session.getMapper(ConversationMemberMapper.class);

        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        S3StorageUtil storage = mock(S3StorageUtil.class);
        PrivateConversationService privateConversation =
                new PrivateConversationService(conversationMapper, memberMapper, users, blocks);
        conversations = new ConversationService(conversationMapper, memberMapper, privateConversation,
                follows, users, storage, mq, mock(PushFanoutPublisher.class), tx);
        ChatContentTypeStrategyRegistry strategies =
                new ChatContentTypeStrategyRegistry(List.of(new TextChatContentStrategy()));
        SyncChainStepRegistry guards = new SyncChainStepRegistry(List.of(
                new TypeValidateStep(strategies), new ConversationStateStep(memberMapper), new SenderBannedStep(),
                new IdempotentStep(messageMapper), new ConversationStep(conversations), new GlobalBannedStep(),
                new InterrelatedRateLimitStep(follows, blocks)));

        messages = new ChatMessageService(messageMapper, conversationMapper, memberMapper, conversations,
                privateConversation, mq, users, mock(ContentInternalPort.class), strategies, guards, tx, blocks,
                follows);
        queries = new ChatMessageQueryService(messageMapper, messages, users, storage, strategies);
        inbox = new ConversationMemberService(memberMapper, conversationMapper, privateConversation, storage, users,
                blocks);
        when(users.getUser(11L)).thenReturn(user(11L));
        when(users.getUser(22L)).thenReturn(user(22L));
        // 收件箱的对端昵称走批量接口
        when(users.listByIds(any())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0);
            return ids.stream().map(FirstPrivateMessageTest::user).toList();
        });
    }

    @AfterEach
    void cleanup() {
        if (jdbc != null) {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void firstMessageMakesTheSameConversationVisibleToBothUsers() {
        assertTrue(inboxFor(11L).isEmpty());
        assertTrue(inboxFor(22L).isEmpty());

        ChatMessageAckDTO first = send(22L, 11L, "first");

        assertEquals(1L, first.getSeq());
        assertConversationInbox(22L, first.getConversationId(), 22L, 0);
        assertConversationInbox(11L, first.getConversationId(), 22L, 1);
        assertEquals(ConversationStatus.WAITING_REPLY, conversationFor(11L, 22L).getStatus());

        ChatMessageAckDTO retry = send(22L, 11L, "first");
        assertEquals(first.getMessageId(), retry.getMessageId());
        assertEquals(first.getConversationId(), retry.getConversationId());
        assertEquals(1, rowCount("message"));
        verify(mq).syncSend(eq(ChatMessageMessage.TOPIC), any(ChatMessageMessage.class));
    }

    @Test
    void openingChatCreatesAnEmptyConversationOnlyForTheClickingUser() {
        String id = openFor(11L, 22L);

        assertEquals(ConversationStatus.ACTIVE, conversationFor(11L, 22L).getStatus());
        assertEquals(1, rowCount("conversation"));
        assertEquals(0L, jdbc.queryForObject("SELECT last_seq FROM conversation WHERE id = ?", Long.class, id));
        assertNull(jdbc.queryForObject("SELECT created_by FROM conversation WHERE id = ?", Long.class, id));
        assertEquals(1, memberCount(id));
        assertEquals(0, rowCount("message"));
        verifyNoInteractions(mq);

        // 只有打开者能看到, 对方的列表和只读查询都看不到
        assertEmptyConversationInbox(inboxFor(11L).get(0), 22L);
        assertTrue(inboxFor(22L).isEmpty());
        assertEmptyConversationInbox(findFor(11L, 22L), 22L);
        assertNull(findFor(22L, 11L));
    }

    @Test
    void repeatedOpeningReusesTheConversationAndTheMemberRow() {
        String id = openFor(11L, 22L);
        openFor(11L, 22L);

        assertEquals(1, rowCount("conversation"));
        assertEquals(1, memberCount(id));
        assertEquals(id, openFor(11L, 22L));
    }

    @Test
    void bothOpeningsShareOneConversationWithTheirOwnMemberRows() {
        String id = openFor(11L, 22L);
        String other = openFor(22L, 11L);

        assertEquals(id, other);
        assertEquals(1, rowCount("conversation"));
        assertEquals(2, memberCount(id));
        assertEquals(1L, memberCountFor(id, 11L));
        assertEquals(1L, memberCountFor(id, 22L));
        assertEquals(0, rowCount("message"));
        assertNull(jdbc.queryForObject("SELECT created_by FROM conversation WHERE id = ?", Long.class, id));
        assertEmptyConversationInbox(inboxFor(11L).get(0), 22L);
        assertEmptyConversationInbox(inboxFor(22L).get(0), 11L);
    }

    @Test
    void firstSendFillsTheMissingMemberRowWhoeverOpensFirst() {
        String id = openFor(11L, 22L);

        // A 只打开了没发, B 直接发首条: B 的成员行被补上, B 成为首条发送人
        ChatMessageAckDTO first = send(22L, 11L, "first");

        assertEquals(id, first.getConversationId());
        assertEquals(1, rowCount("conversation"));
        assertEquals(2, memberCount(id));
        assertEquals(1, rowCount("message"));
        assertEquals(22L, jdbc.queryForObject("SELECT created_by FROM conversation WHERE id = ?", Long.class, id));
        assertEquals(ConversationStatus.WAITING_REPLY, conversationFor(11L, 22L).getStatus());
        assertConversationInbox(22L, id, 22L, 0);
        assertConversationInbox(11L, id, 22L, 1);
    }

    @Test
    void openingAndFirstSendRaceShareTheConversation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> opening = executor.submit(() -> openFor(11L, 22L));
            Future<ChatMessageAckDTO> sending = executor.submit(() -> send(22L, 11L, "first"));
            String openedId = opening.get(10, TimeUnit.SECONDS);
            ChatMessageAckDTO sent = sending.get(10, TimeUnit.SECONDS);

            assertEquals(openedId, sent.getConversationId());
            assertEquals(1, rowCount("conversation"));
            assertEquals(2, memberCount(openedId));
            assertEquals(1, rowCount("message"));
            assertEquals(22L, jdbc.queryForObject("SELECT created_by FROM conversation WHERE id = ?", Long.class, openedId));
            assertConversationInbox(22L, openedId, 22L, 0);
            assertConversationInbox(11L, openedId, 22L, 1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedFirstSendRollsBackAndKeepsTheEmptyConversation() {
        String id = openFor(11L, 22L);
        jdbc.execute("ALTER TABLE message ADD CONSTRAINT reject_sender_22 CHECK (sender_id <> 22)");

        // 首发事务整体回滚: createdBy/状态/消息回到打开时的样子; B 的补行残留但不可见
        assertThrows(DataIntegrityViolationException.class, () -> send(22L, 11L, "failed"));

        assertEquals(1, rowCount("conversation"));
        assertEquals(2, memberCount(id));
        assertEquals(1L, visibleMemberCount(id));
        assertEquals(0, rowCount("message"));
        assertNull(jdbc.queryForObject("SELECT created_by FROM conversation WHERE id = ?", Long.class, id));
        assertEquals(ConversationStatus.ACTIVE, conversationFor(11L, 22L).getStatus());
        assertTrue(inboxFor(22L).isEmpty());
        verifyNoInteractions(mq);

        // 已打开的空会话仍然保留, 换个方向的首发仍能成功
        ChatMessageAckDTO first = send(11L, 22L, "first");
        assertEquals(id, first.getConversationId());
        assertEquals(1L, first.getSeq());
        assertConversationInbox(11L, id, 11L, 0);
        assertConversationInbox(22L, id, 11L, 1);
    }

    @Test
    void failedFirstSendWithoutAnyConversationLeavesNoRows() {
        jdbc.execute("ALTER TABLE message ADD CONSTRAINT reject_sender_11 CHECK (sender_id <> 11)");

        assertThrows(DataIntegrityViolationException.class, () -> send(11L, 22L, "failed"));

        assertEquals(0, rowCount("conversation"));
        assertEquals(0, rowCount("conversation_member"));
        assertEquals(0, rowCount("message"));
        verifyNoInteractions(mq);
    }

    @Test
    void deletingAnEmptyChatHidesOnlyTheOwnerAndReopeningReusesIt() {
        String id = openFor(11L, 22L);
        UserContext.setUser("11");
        try {
            conversations.deleteConversation(id);
        } finally {
            UserContext.removeUser();
        }

        assertTrue(inboxFor(11L).isEmpty());
        assertNull(findFor(11L, 22L));
        assertEquals(1, rowCount("conversation"));
        assertEquals(1, memberCount(id));

        // 再次打开复用同一个会话
        assertEquals(id, openFor(11L, 22L));
        assertEquals(1, rowCount("conversation"));
        assertEquals(1, memberCount(id));
        assertEmptyConversationInbox(inboxFor(11L).get(0), 22L);
    }

    @Test
    void lookingUpAPrivateChatLeavesBothInboxesUnchanged() {
        assertNull(findFor(11L, 22L));
        assertNull(findFor(22L, 11L));
        assertEquals(0, rowCount("conversation"));
        assertEquals(0, rowCount("conversation_member"));

        String id = send(11L, 22L, "first").getConversationId();
        assertEquals(id, conversationFor(11L, 22L).getConversationId());
        assertEquals(id, conversationFor(22L, 11L).getConversationId());

        jdbc.update("UPDATE conversation_member SET inbox_visible = 0 WHERE conversation_id = ? AND user_id = 11", id);
        assertNull(conversationFor(11L, 22L));
        assertTrue(inboxFor(11L).isEmpty());
        assertEquals(id, conversationFor(22L, 11L).getConversationId());
    }

    @Test
    void hidingAPrivateChatKeepsHistoryAndDoesNotSendAReadReceipt() {
        String id = send(11L, 22L, "first").getConversationId();
        long visibleAfter = memberLong(id, 22L, "visible_after_seq");
        int messagesBefore = rowCount("message");

        deleteAs(22L, id);

        assertTrue(inboxFor(22L).isEmpty());
        assertEquals(1, inboxFor(11L).size());
        assertEquals(0, memberInt(id, 22L, "inbox_visible"));
        assertEquals(1, memberInt(id, 11L, "inbox_visible"));
        assertEquals(1L, memberLong(id, 22L, "last_read_seq"));
        assertEquals(visibleAfter, memberLong(id, 22L, "visible_after_seq"));
        assertEquals(messagesBefore, rowCount("message"));
        assertEquals(List.of(1L), visibleSequences(22L, id));
        verify(mq, never()).syncSend(eq(ReadMessageMessage.TOPIC), any(ReadMessageMessage.class));
    }

    @Test
    void hidingAPrivateChatAgainLeavesTheReadCursorInPlace() {
        String id = send(11L, 22L, "first").getConversationId();
        deleteAs(22L, id);
        long readSeq = memberLong(id, 22L, "last_read_seq");

        deleteAs(22L, id);

        assertEquals(readSeq, memberLong(id, 22L, "last_read_seq"));
        assertEquals(0, memberInt(id, 22L, "inbox_visible"));
        assertTrue(inboxFor(22L).isEmpty());
        assertEquals(1, rowCount("message"));
        verify(mq, never()).syncSend(eq(ReadMessageMessage.TOPIC), any(ReadMessageMessage.class));
    }

    @Test
    void aNewMessageAfterHidingAddsOnlyOneUnread() {
        String id = send(11L, 22L, "first").getConversationId();
        deleteAs(22L, id);
        when(follows.interrelated(any())).thenReturn(true);

        send(11L, 22L, "second");

        assertConversationInbox(22L, id, 11L, 1);
        assertEquals(1, memberInt(id, 22L, "inbox_visible"));
        assertEquals(List.of(1L, 2L), visibleSequences(22L, id));
        assertEquals(0L, memberLong(id, 22L, "visible_after_seq"));
    }

    @Test
    void passingAHiddenConversationKeepsTheUnreadTotal() {
        when(users.getUser(33L)).thenReturn(user(33L));
        String hidden = send(22L, 11L, "from-22").getConversationId();
        String visible = send(33L, 11L, "from-33").getConversationId();
        deleteAs(11L, hidden);

        UserContext.setUser("11");
        try {
            ConversationUnreadVO plain = inbox.listUnread(null);
            ConversationUnreadVO active = inbox.listUnread(hidden);

            assertEquals(1L, plain.getTotalUnread());
            assertEquals(plain.getTotalUnread(), active.getTotalUnread());
            assertEquals(List.of(visible), unreadIds(plain));
            assertEquals(List.of(visible, hidden), unreadIds(active));
            ConversationUnreadItemVO hiddenItem = active.getConversations().get(1);
            assertEquals(1L, hiddenItem.getLastSeq());
            assertEquals(0L, hiddenItem.getUnreadCount().longValue());
        } finally {
            UserContext.removeUser();
        }
    }

    @Test
    void hidingAndClearingArePersonalAndNewMessagesRestoreTheListItem() {
        String id = send(11L, 22L, "first").getConversationId();
        UserContext.setUser("22");
        try {
            messages.clearHistory(id);
            conversations.deleteConversation(id);
        } finally {
            UserContext.removeUser();
        }

        assertTrue(inboxFor(22L).isEmpty());
        when(follows.interrelated(any())).thenReturn(true);
        send(11L, 22L, "second");

        assertConversationInbox(22L, id, 11L, 1);
        assertEquals(List.of(2L), visibleSequences(22L, id));
        assertEquals(List.of(1L, 2L), visibleSequences(11L, id));
    }

    @Test
    void blockStopsPrivateSendsAndLeavesHistoryReadable() {
        String id = send(11L, 22L, "first").getConversationId();
        when(blocks.blockedAuthors(22L)).thenReturn(List.of(11L));
        when(blocks.blocksPrivateChat(22L, 11L)).thenReturn(true);
        when(blocks.blocksPrivateChat(11L, 22L)).thenReturn(true);

        // 拉黑后已有会话仍能打开看历史, 但不能再发
        assertEquals(id, openFor(22L, 11L));
        assertEquals(Boolean.TRUE, conversationFor(22L, 11L).getBlockedByMe());
        assertEquals(List.of(1L), visibleSequences(22L, id));
        assertThrows(BizException.class, () -> send(22L, 11L, "blocked reply"));
        assertThrows(BizException.class, () -> send(11L, 22L, "blocked send"));
        assertEquals(1, rowCount("message"));

        when(blocks.blocksPrivateChat(22L, 11L)).thenReturn(false);
        assertEquals(2L, send(22L, 11L, "reply").getSeq());
    }

    @Test
    void blockingPreventsOpeningOrSendingInANewPrivateConversation() {
        when(blocks.blocksPrivateChat(11L, 22L)).thenReturn(true);

        assertThrows(BizException.class, () -> openFor(11L, 22L));
        assertThrows(BizException.class, () -> send(11L, 22L, "blocked"));
        assertEquals(0, rowCount("conversation"));
        assertEquals(0, rowCount("conversation_member"));
        assertEquals(0, rowCount("message"));
        verifyNoInteractions(mq);
    }

    @Test
    void aSharedGroupDoesNotCountAsAPrivateChat() {
        jdbc.update("INSERT INTO conversation (id, type, created_by) VALUES ('group', 'GROUP', 11)");
        jdbc.update("""
                INSERT INTO conversation_member (id, conversation_id, user_id)
                VALUES ('member11', 'group', 11), ('member22', 'group', 22)
                """);

        assertNull(findFor(11L, 22L));
        ChatMessageAckDTO first = send(11L, 22L, "first");
        assertEquals(first.getConversationId(), conversationFor(22L, 11L).getConversationId());
        assertEquals(2, rowCount("conversation"));
    }

    @Test
    void mutualFirstSendKeepsConversationActiveAfterUnfollowing() {
        when(follows.interrelated(any())).thenReturn(true);

        ChatMessageAckDTO first = send(11L, 22L, "first");
        ChatMessageAckDTO second = send(11L, 22L, "second");

        assertEquals(first.getConversationId(), second.getConversationId());
        assertEquals(2L, second.getSeq());
        assertConversationInbox(22L, first.getConversationId(), 11L, 2);
        assertEquals(ConversationStatus.ACTIVE, conversationFor(11L, 22L).getStatus());

        when(follows.interrelated(any())).thenReturn(false);
        assertEquals(3L, send(11L, 22L, "after-unfollow").getSeq());
        // 只有首发查过互关: 会话互关建立后即 ACTIVE, 后续发送不再查
        verify(follows, times(1)).interrelated(any());
    }

    @Test
    void waitingConversationBecomesActiveAfterTheInitiatorSendsWhileMutual() {
        send(11L, 22L, "first");
        assertThrows(BizException.class, () -> send(11L, 22L, "second"));

        when(follows.interrelated(any())).thenReturn(true);
        assertEquals(2L, send(11L, 22L, "second").getSeq());
        assertEquals(ConversationStatus.ACTIVE, conversationFor(11L, 22L).getStatus());

        when(follows.interrelated(any())).thenReturn(false);
        assertEquals(3L, send(11L, 22L, "after-unfollow").getSeq());
    }

    @Test
    void aClientMessageIdCannotCreateASecondConversation() {
        when(users.getUser(33L)).thenReturn(user(33L));
        send(11L, 22L, "first");

        BizException rejected = assertThrows(BizException.class, () -> send(11L, 33L, "first"));

        assertEquals(ErrorCode.STATE_CONFLICT, rejected.getErrorCode());
        assertEquals(1, rowCount("conversation"));
        assertEquals(2, rowCount("conversation_member"));
        assertTrue(inboxFor(33L).isEmpty());
    }

    @Test
    void simultaneousFirstSendsShareAConversationAndRecordWhoSentFirst() throws Exception {
        java.util.concurrent.CyclicBarrier startTogether = new java.util.concurrent.CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<ChatMessageAckDTO> from11 = () -> {
                startTogether.await(5, TimeUnit.SECONDS);
                return send(11L, 22L, "from-11");
            };
            Callable<ChatMessageAckDTO> from22 = () -> {
                startTogether.await(5, TimeUnit.SECONDS);
                return send(22L, 11L, "from-22");
            };
            Future<ChatMessageAckDTO> f11 = executor.submit(from11);
            Future<ChatMessageAckDTO> f22 = executor.submit(from22);
            ChatMessageAckDTO first = f11.get(10, TimeUnit.SECONDS);
            ChatMessageAckDTO second = f22.get(10, TimeUnit.SECONDS);

            assertEquals(first.getConversationId(), second.getConversationId());
            assertEquals(Set.of(1L, 2L), Set.of(first.getSeq(), second.getSeq()));
            // 唯一键只留一个会话, 建会话的人就是 seq=1 的发送人
            assertEquals(first.getSeq() == 1L ? 11L : 22L,
                    jdbc.queryForObject("SELECT created_by FROM conversation", Long.class));
            assertEquals(1, rowCount("conversation"));
            assertEquals(2, rowCount("conversation_member"));
            assertEquals(2, rowCount("message"));
            assertEquals(ConversationStatus.ACTIVE, conversationFor(11L, 22L).getStatus());
            verify(mq, times(2)).syncSend(eq(ChatMessageMessage.TOPIC), any(ChatMessageMessage.class));
        } finally {
            executor.shutdownNow();
        }
    }

    private void deleteAs(long userId, String conversationId) {
        UserContext.setUser(Long.toString(userId));
        try {
            assertEquals("删除成功", conversations.deleteConversation(conversationId));
        } finally {
            UserContext.removeUser();
        }
    }

    private long memberLong(String conversationId, long userId, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM conversation_member WHERE conversation_id = ? AND user_id = ?",
                Long.class, conversationId, userId);
    }

    private int memberInt(String conversationId, long userId, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM conversation_member WHERE conversation_id = ? AND user_id = ?",
                Integer.class, conversationId, userId);
    }

    private static List<String> unreadIds(ConversationUnreadVO result) {
        return result.getConversations().stream().map(ConversationUnreadItemVO::getConversationId).toList();
    }

    private ChatMessageAckDTO send(long senderId, long peerId, String clientMsgId) {
        UserContext.setUser(Long.toString(senderId));
        try {
            ChatContent content = new ChatContent();
            content.setText("你好");
            ChatMessageDTO request = new ChatMessageDTO();
            request.setPeerUserId(peerId);
            request.setClientMsgId(clientMsgId);
            request.setContentType(ChatContentTypeEnum.TEXT);
            request.setContent(content);
            return messages.send(request);
        } finally {
            UserContext.removeUser();
        }
    }

    private String openFor(long userId, long peerId) {
        UserContext.setUser(Long.toString(userId));
        try {
            return inbox.openPrivate(peerId).getConversationId();
        } finally {
            UserContext.removeUser();
        }
    }

    private List<Long> visibleSequences(long userId, String conversationId) {
        UserContext.setUser(Long.toString(userId));
        try {
            return queries.getMessageList(conversationId, null, MessageDirectionEnum.BEFORE).items().stream()
                    .map(MessageItemVO::getSeq).sorted().toList();
        } finally {
            UserContext.removeUser();
        }
    }

    private List<UserConversationVO> inboxFor(long userId) {
        UserContext.setUser(Long.toString(userId));
        try {
            return inbox.listInbox(null).items();
        } finally {
            UserContext.removeUser();
        }
    }

    private UserConversationVO findFor(long userId, long peerId) {
        UserContext.setUser(Long.toString(userId));
        try {
            return inbox.findPrivateInbox(peerId);
        } finally {
            UserContext.removeUser();
        }
    }

    private UserConversationVO conversationFor(long userId, long peerId) {
        return findFor(userId, peerId);
    }

    private void assertConversationInbox(long userId, String conversationId, long initiator, int unread) {
        List<UserConversationVO> items = inboxFor(userId);
        assertEquals(1, items.size());
        UserConversationVO item = items.get(0);
        assertEquals(conversationId, item.getConversationId());
        assertEquals(initiator, item.getCreatedBy());
        assertEquals(unread, item.getUnreadCount().longValue());
        assertEquals("你好", item.getLastMessageContent());
    }

    private void assertEmptyConversationInbox(UserConversationVO item, long peerId) {
        assertEquals("用户" + peerId, item.getConversationName());
        assertNull(item.getCreatedBy());
        assertEquals(0L, item.getUnreadCount().longValue());
        assertNull(item.getLastMessageContent());
    }

    private long memberCount(String conversationId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_member WHERE conversation_id = ?", Long.class, conversationId);
    }

    private long memberCountFor(String conversationId, long userId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM conversation_member
                WHERE conversation_id = ? AND user_id = ?
                """, Long.class, conversationId, userId);
    }

    private long visibleMemberCount(String conversationId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM conversation_member
                WHERE conversation_id = ? AND inbox_visible = 1
                """, Long.class, conversationId);
    }

    private int rowCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static UserSummary user(long id) {
        UserSummary user = new UserSummary();
        user.setId(id);
        user.setNickname("用户" + id);
        return user;
    }
}
