package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.domain.enums.MessageStatusEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.RecallTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.vo.MessageItemVO;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.chat.mapper.MessageMapper;
import cn.diaojiahong.myyoutube.chat.service.strategy.ChatContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.chat.service.strategy.TextChatContentStrategy;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息查询的游标语义在真实 SQL 上验证: 历史向旧翻页、增量向新推进、
 * visibleAfterSeq 是两条查询共同的可见下限、撤回行保留但正文隐藏。
 */
class MessageQueryPageTest {

    private JdbcTemplate jdbc;
    private ChatMessageQueryService queries;
    private UserPort users;

    @BeforeEach
    void setup() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:message-query-page-" + UUID.randomUUID()
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
        users = mock(UserPort.class);
        ChatContentTypeStrategyRegistry strategies =
                new ChatContentTypeStrategyRegistry(List.of(new TextChatContentStrategy()));
        PrivateConversationService privateConversation =
                new PrivateConversationService(conversationMapper, memberMapper, users, mock(UserBlockPort.class));
        ConversationService conversations = new ConversationService(conversationMapper, memberMapper,
                privateConversation, mock(FollowPort.class), users, storage, mock(RocketMQTemplate.class),
                mock(PushFanoutPublisher.class), tx);
        ChatMessageService messages = new ChatMessageService(messageMapper, conversationMapper, memberMapper,
                conversations, privateConversation, mock(RocketMQTemplate.class), users, mock(ContentInternalPort.class),
                strategies, mock(SyncChainStepRegistry.class), tx,
                mock(UserBlockPort.class), mock(FollowPort.class));
        queries = new ChatMessageQueryService(messageMapper, messages, users, storage, strategies);

        jdbc.update("INSERT INTO conversation (id, type, created_by) VALUES ('group-1', 'GROUP', 11)");
        jdbc.update("INSERT INTO conversation_member (id, conversation_id, user_id) VALUES ('member11', 'group-1', 11)");
        when(users.listByIds(any())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0);
            return ids.stream().map(MessageQueryPageTest::user).toList();
        });
    }

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
        if (jdbc != null) {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void historyPagingWalksTowardOlderMessages() {
        seedMessages(25);

        CursorPage<MessageItemVO, Long> page = history(null);
        assertEquals(20, page.items().size());
        assertEquals(25L, page.items().get(0).getSeq());
        assertEquals(6L, page.items().get(19).getSeq());
        assertTrue(page.hasMore());
        assertEquals(6L, page.nextCursor());

        CursorPage<MessageItemVO, Long> next = history(page.nextCursor());
        assertEquals(5, next.items().size());
        assertEquals(5L, next.items().get(0).getSeq());
        assertEquals(1L, next.items().get(4).getSeq());
        assertFalse(next.hasMore());
        assertNull(next.nextCursor());
    }

    @Test
    void syncPagingAdvancesTowardNewerMessages() {
        seedMessages(25);

        CursorPage<MessageItemVO, Long> first = sync(null);
        assertEquals(1L, first.items().get(0).getSeq());
        assertEquals(20L, first.items().get(19).getSeq());
        assertTrue(first.hasMore());
        assertEquals(20L, first.nextCursor());

        CursorPage<MessageItemVO, Long> next = sync(20L);
        assertEquals(21L, next.items().get(0).getSeq());
        assertEquals(5, next.items().size());
        assertFalse(next.hasMore());

        // lastSeq 落在可见下限之下时, 增量从下限开始, 不回放已经清掉的历史
        CursorPage<MessageItemVO, Long> replayed = sync(10L);
        assertEquals(11L, replayed.items().get(0).getSeq());
    }

    @Test
    void visibleFloorAppliesToHistoryAndSyncAlike() {
        seedMessages(25);
        jdbc.update("UPDATE conversation_member SET visible_after_seq = 23 WHERE id = 'member11'");

        List<MessageItemVO> history = history(null).items();
        assertEquals(2, history.size());
        assertEquals(25L, history.get(0).getSeq());
        assertEquals(24L, history.get(1).getSeq());
        assertEquals(List.of(24L, 25L), seqsOf(sync(null)));
        assertEquals(List.of(24L, 25L), seqsOf(sync(1L)));
    }

    @Test
    void recalledMessageKeepsItsRowButHidesContent() {
        seedMessages(3);
        jdbc.update(
                "UPDATE message SET recalled = 1, status = 'WITHDRAWN', recall_type = 'SENDER' WHERE seq = 2");

        List<MessageItemVO> items = history(null).items();
        assertEquals(3, items.size());
        MessageItemVO recalled = items.get(1);
        assertEquals(2L, recalled.getSeq());
        assertNull(recalled.getContent());
        assertEquals(MessageStatusEnum.WITHDRAWN, recalled.getStatus());
        assertEquals(RecallTypeEnum.SENDER, recalled.getRecallType());
        assertEquals("消息1", items.get(2).getContent().getText());

        // 增量同步同样返回撤回行, 正文同样隐藏
        List<MessageItemVO> synced = sync(null).items();
        assertEquals(3, synced.size());
        assertNull(synced.get(1).getContent());
    }

    @Test
    void departedSenderKeepsNicknameViaPageBatchLoad() {
        insertMessage(2, 11L);
        insertMessage(1, 99L);

        List<MessageItemVO> items = history(null).items();

        // 99 不在成员名单里, 发送人资料仍按本页 senderId 批量补齐
        assertEquals("用户11", items.get(0).getSenderNickName());
        assertEquals("用户99", items.get(1).getSenderNickName());
        verify(users).listByIds(List.of(11L, 99L));
    }

    private void seedMessages(int count) {
        for (long seq = 1; seq <= count; seq++) {
            insertMessage(seq, 11L);
        }
    }

    private void insertMessage(long seq, long senderId) {
        jdbc.update("""
                INSERT INTO message (id, conversation_id, sender_id, content_type, content, client_msg_id, seq, status)
                VALUES (?, 'group-1', ?, 'TEXT', ?, ?, ?, 'SENT')
                """, "m-" + seq, senderId, "{\"text\":\"消息" + seq + "\"}", "client-" + seq, seq);
    }

    private CursorPage<MessageItemVO, Long> history(Long cursor) {
        return asUser(() -> queries.getMessageList("group-1", cursor));
    }

    private CursorPage<MessageItemVO, Long> sync(Long lastSeq) {
        return asUser(() -> queries.syncMessageList("group-1", lastSeq));
    }

    private List<Long> seqsOf(CursorPage<MessageItemVO, Long> page) {
        return page.items().stream().map(MessageItemVO::getSeq).sorted().toList();
    }

    private <T> T asUser(Supplier<T> call) {
        UserContext.setUser("11");
        try {
            return call.get();
        } finally {
            UserContext.removeUser();
        }
    }

    private static UserSummary user(long id) {
        UserSummary user = new UserSummary();
        user.setId(id);
        user.setNickname("用户" + id);
        return user;
    }
}
