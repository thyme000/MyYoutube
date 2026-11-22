package cn.diaojiahong.myyoutube.chat.mapper;

import cn.diaojiahong.myyoutube.chat.domain.dto.PrivateInboxHideState;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationStatus;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 序号在会话上递增；列表尾巴写在成员行上，撤回只拨中这条的人。
 */
class ConversationRecallSqlTest {

    private JdbcTemplate jdbc;
    private SqlSession session;
    private ConversationMapper conversations;
    private ConversationMemberMapper members;

    @BeforeEach
    void setup() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:conversation-recall;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS conversation_member");
        jdbc.execute("DROP TABLE IF EXISTS conversation");
        jdbc.execute("""
                CREATE TABLE conversation (
                    id VARCHAR(32) PRIMARY KEY,
                    type VARCHAR(16),
                    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    last_seq BIGINT NOT NULL DEFAULT 0,
                    created_by BIGINT,
                    user_a BIGINT,
                    user_b BIGINT,
                    updated_at TIMESTAMP,
                    CONSTRAINT uk_private_pair UNIQUE (user_a, user_b)
                )
                """);
        jdbc.execute("""
                CREATE TABLE conversation_member (
                    id VARCHAR(32) PRIMARY KEY,
                    conversation_id VARCHAR(32) NOT NULL,
                    user_id BIGINT NOT NULL,
                    last_read_seq BIGINT NOT NULL DEFAULT 0,
                    visible_after_seq BIGINT NOT NULL DEFAULT 0,
                    last_message_id VARCHAR(64),
                    last_message_preview VARCHAR(64),
                    last_message_sender_nickname VARCHAR(64),
                    last_message_at TIMESTAMP,
                    is_last_message_recall TINYINT,
                    inbox_visible TINYINT NOT NULL DEFAULT 1,
                    deleted TINYINT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP
                )
                """);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(
                new ClassPathResource("mapper/ConversationMapper.xml"),
                new ClassPathResource("mapper/ConversationMemberMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        session = factory.openSession(true);
        conversations = session.getMapper(ConversationMapper.class);
        members = session.getMapper(ConversationMemberMapper.class);
        jdbc.update("INSERT INTO conversation(id, last_seq) VALUES ('c1', 0)");
    }

    @AfterEach
    void cleanup() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void sequenceAdvancesOnTheConversationRow() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 20, 10, 0);
        assertEquals(1, conversations.advanceOnMessage("c1", now));
        assertEquals(1L, conversations.selectLastSeq("c1"));
        jdbc.update("UPDATE conversation SET status = 'WAITING_REPLY' WHERE id = 'c1'");
        assertEquals(1, conversations.advanceOnMessage("c1", now));
        assertEquals(2L, conversations.selectLastSeq("c1"));
        jdbc.update("UPDATE conversation SET status = 'CLOSED' WHERE id = 'c1'");
        assertEquals(0, conversations.advanceOnMessage("c1", now));
        assertEquals(2L, conversations.selectLastSeq("c1"));
    }

    @Test
    void activatesAWaitingConversationOnce() {
        jdbc.update("UPDATE conversation SET status = 'WAITING_REPLY' WHERE id = 'c1'");
        assertEquals(1, conversations.activatePrivateChat("c1"));
        assertEquals(ConversationStatus.ACTIVE, conversations.lockById("c1").getStatus());
        assertEquals(0, conversations.activatePrivateChat("c1"));
    }

    @Test
    void findsAPrivateChatByTheSortedPair() {
        jdbc.update("INSERT INTO conversation(id, type, user_a, user_b) VALUES ('pair', 'SINGLE', 7, 22)");

        assertEquals("pair", conversations.findPrivateChat(7L, 22L).getId());
        assertNull(conversations.findPrivateChat(7L, 33L));
    }

    @Test
    void tailIsWrittenOnlyForMembersStillInTheConversation() {
        seed("m-active", "c1", 0, null);
        seed("m-left", "c1", 1, null);
        LocalDateTime now = LocalDateTime.of(2026, 11, 20, 10, 1);
        assertEquals(1, applyTail("c1", "msg-1", now, "你好", "爱丽丝"));
        assertEquals("msg-1", previewId("m-active"));
        assertEquals("你好", previewText("m-active"));
        assertEquals("爱丽丝", nickname("m-active"));
        assertEquals(0, flag("m-active"));
        assertNull(previewId("m-left"));
    }

    @Test
    void privateTailClearsTheNickname() {
        seed("m-active", "c1", 0, "旧昵称");
        applyTail("c1", "msg-1", LocalDateTime.now(), "你好", null);
        assertNull(nickname("m-active"));
    }

    @Test
    void recallingTheLastMessageMarksOnlyThatTail() {
        seed("m-last", "c1", 0, null);
        jdbc.update("UPDATE conversation_member SET last_message_id = 'm2', is_last_message_recall = 0 WHERE id = 'm-last'");
        seed("m-older", "c1", 0, null);
        jdbc.update("UPDATE conversation_member SET last_message_id = 'm1', is_last_message_recall = 0 WHERE id = 'm-older'");

        assertEquals(1, markTailRecalled("c1", "m2", LocalDateTime.now()));
        assertEquals(1, flag("m-last"));
        assertEquals(0, flag("m-older"));
    }

    @Test
    void readsTheConversationSeqAndMemberTailTogether() {
        jdbc.update("UPDATE conversation SET last_seq = 6 WHERE id = 'c1'");
        insertHideRow("mine", 11L, 1L, 2L, "msg-1", 1, 0);

        PrivateInboxHideState state = members.findInboxHideState("c1", 11L);

        assertEquals(6L, state.getLastSeq());
        assertEquals("mine", state.getMemberId());
        assertEquals("msg-1", state.getLastMessageId());
        assertEquals(Boolean.TRUE, state.getInboxVisible());
        assertEquals(Boolean.FALSE, state.getDeleted());
        assertNull(members.findInboxHideState("c1", 99L));
    }

    @Test
    void hideInboxAdvancesTheReadCursorAndLeavesHistory() {
        insertHideRow("mine", 11L, 1L, 2L, "msg-1", 1, 0);
        LocalDateTime now = LocalDateTime.of(2026, 11, 22, 9, 0);

        assertEquals(1, members.hideInbox("mine", "msg-1", 4L, now));

        assertEquals(0, inboxVisible("mine"));
        assertEquals(4L, readSeq("mine"));
        assertEquals(2L, visibleAfter("mine"));
        assertEquals("msg-1", previewId("mine"));
    }

    @Test
    void hideInboxDoesNotRewindTheReadCursor() {
        insertHideRow("mine", 11L, 8L, 2L, "msg-1", 1, 0);

        assertEquals(1, members.hideInbox("mine", "msg-1", 4L, LocalDateTime.of(2026, 11, 22, 9, 1)));

        assertEquals(0, inboxVisible("mine"));
        assertEquals(8L, readSeq("mine"));
        assertEquals(2L, visibleAfter("mine"));
    }

    @Test
    void hideInboxMatchesANullTail() {
        insertHideRow("mine", 11L, 0L, 0L, null, 1, 0);

        assertEquals(1, members.hideInbox("mine", null, 0L, LocalDateTime.of(2026, 11, 22, 9, 2)));

        assertEquals(0, inboxVisible("mine"));
        assertNull(previewId("mine"));
    }

    @Test
    void hideInboxMatchesNothingWhenTheTailChanges() {
        insertHideRow("mine", 11L, 1L, 2L, "msg-2", 1, 0);

        assertEquals(0, members.hideInbox("mine", "msg-1", 9L, LocalDateTime.of(2026, 11, 22, 9, 3)));
        assertEquals(0, members.hideInbox("mine", null, 9L, LocalDateTime.of(2026, 11, 22, 9, 4)));

        assertEquals(1, inboxVisible("mine"));
        assertEquals(1L, readSeq("mine"));
        assertEquals(2L, visibleAfter("mine"));
        assertEquals("msg-2", previewId("mine"));
    }

    @Test
    void hideInboxMatchesNothingWhenHiddenOrDeleted() {
        insertHideRow("hidden", 11L, 3L, 1L, "msg-1", 0, 0);
        insertHideRow("left", 12L, 3L, 1L, "msg-1", 1, 1);
        LocalDateTime now = LocalDateTime.of(2026, 11, 22, 9, 5);

        assertEquals(0, members.hideInbox("hidden", "msg-1", 9L, now));
        assertEquals(0, members.hideInbox("left", "msg-1", 9L, now));

        assertEquals(0, inboxVisible("hidden"));
        assertEquals(3L, readSeq("hidden"));
        assertEquals(1, inboxVisible("left"));
        assertEquals(3L, readSeq("left"));
    }

    @Test
    void activeUserIdsSkipMembersWhoLeftAndKeepRowIdOrder() {
        jdbc.update("""
                INSERT INTO conversation_member(id, conversation_id, user_id, deleted)
                VALUES ('2', 'c1', 100, 0),
                       ('1', 'c1', 200, 0),
                       ('0', 'c1', 300, 1),
                       ('3', 'other', 400, 0)
                """);

        List<Long> userIds = members.selectActiveUserIds("c1");

        assertEquals(List.of(200L, 100L), userIds);
    }

    private int applyTail(String conversationId, String messageId, LocalDateTime at, String preview, String nickname) {
        return members.update(null, new LambdaUpdateWrapper<ConversationMember>()
                .set(ConversationMember::getLastMessageId, messageId)
                .set(ConversationMember::getInboxVisible, true)
                .set(ConversationMember::getLastMessagePreview, preview)
                .set(ConversationMember::getLastMessageSenderNickname, nickname)
                .set(ConversationMember::getLastMessageAt, at)
                .set(ConversationMember::getIsLastMessageRecall, false)
                .set(ConversationMember::getUpdatedAt, at)
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getDeleted, false));
    }

    private int markTailRecalled(String conversationId, String messageId, LocalDateTime recalledAt) {
        return members.update(null, new LambdaUpdateWrapper<ConversationMember>()
                .set(ConversationMember::getIsLastMessageRecall, true)
                .set(ConversationMember::getUpdatedAt, recalledAt)
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getLastMessageId, messageId)
                .eq(ConversationMember::getDeleted, false));
    }

    private void insertHideRow(String id, long userId, long lastReadSeq, long visibleAfterSeq,
                               String lastMessageId, int inboxVisible, int deleted) {
        jdbc.update("""
                INSERT INTO conversation_member(
                    id, conversation_id, user_id, last_read_seq, visible_after_seq,
                    last_message_id, inbox_visible, deleted)
                VALUES (?, 'c1', ?, ?, ?, ?, ?, ?)
                """, id, userId, lastReadSeq, visibleAfterSeq, lastMessageId, inboxVisible, deleted);
    }

    private int inboxVisible(String id) {
        return jdbc.queryForObject(
                "SELECT inbox_visible FROM conversation_member WHERE id = ?", Integer.class, id);
    }

    private long readSeq(String id) {
        return jdbc.queryForObject(
                "SELECT last_read_seq FROM conversation_member WHERE id = ?", Long.class, id);
    }

    private long visibleAfter(String id) {
        return jdbc.queryForObject(
                "SELECT visible_after_seq FROM conversation_member WHERE id = ?", Long.class, id);
    }

    private void seed(String id, String conversationId, int deleted, String nickname) {
        jdbc.update("""
                INSERT INTO conversation_member(id, conversation_id, user_id, deleted, last_message_sender_nickname)
                VALUES (?, ?, 7, ?, ?)
                """, id, conversationId, deleted, nickname);
    }

    private String previewId(String id) {
        return jdbc.queryForObject(
                "SELECT last_message_id FROM conversation_member WHERE id = ?", String.class, id);
    }

    private String previewText(String id) {
        return jdbc.queryForObject(
                "SELECT last_message_preview FROM conversation_member WHERE id = ?", String.class, id);
    }

    private String nickname(String id) {
        return jdbc.queryForObject(
                "SELECT last_message_sender_nickname FROM conversation_member WHERE id = ?", String.class, id);
    }

    private int flag(String id) {
        return jdbc.queryForObject(
                "SELECT is_last_message_recall FROM conversation_member WHERE id = ?", Integer.class, id);
    }
}
