package cn.diaojiahong.myyoutube.chat.mapper;

import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationStatus;

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
        assertEquals(1, members.applyTail("c1", "msg-1", now, "你好", "爱丽丝"));
        assertEquals("msg-1", previewId("m-active"));
        assertEquals("你好", previewText("m-active"));
        assertEquals("爱丽丝", nickname("m-active"));
        assertEquals(0, flag("m-active"));
        assertNull(previewId("m-left"));
    }

    @Test
    void privateTailClearsTheNickname() {
        seed("m-active", "c1", 0, "旧昵称");
        members.applyTail("c1", "msg-1", LocalDateTime.now(), "你好", null);
        assertNull(nickname("m-active"));
    }

    @Test
    void recallingTheLastMessageMarksOnlyThatTail() {
        seed("m-last", "c1", 0, null);
        jdbc.update("UPDATE conversation_member SET last_message_id = 'm2', is_last_message_recall = 0 WHERE id = 'm-last'");
        seed("m-older", "c1", 0, null);
        jdbc.update("UPDATE conversation_member SET last_message_id = 'm1', is_last_message_recall = 0 WHERE id = 'm-older'");

        assertEquals(1, members.markTailRecalled("c1", "m2", LocalDateTime.now()));
        assertEquals(1, flag("m-last"));
        assertEquals(0, flag("m-older"));
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
