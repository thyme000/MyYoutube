package cn.diaojiahong.myyoutube.chat.mapper;

import cn.diaojiahong.myyoutube.chat.domain.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.MessageStatusEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.ChatContent;
import cn.diaojiahong.myyoutube.chat.domain.po.Message;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * content 经 JacksonTypeHandler 整体序列化落 JSON 列, 转发封面 key 必须真的存进去并能读回来。
 */
class MessageContentRoundTripTest {

    private JdbcTemplate jdbc;
    private SqlSession session;
    private MessageMapper messages;

    @BeforeEach
    void setup() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:message-roundtrip;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS message");
        jdbc.execute("""
                CREATE TABLE message (
                    id VARCHAR(64) PRIMARY KEY,
                    conversation_id VARCHAR(32) NOT NULL,
                    sender_id BIGINT NOT NULL,
                    content_type VARCHAR(32) NOT NULL,
                    content TEXT,
                    client_msg_id VARCHAR(128),
                    seq BIGINT NOT NULL,
                    status VARCHAR(32),
                    recalled TINYINT,
                    recalled_at TIMESTAMP,
                    recall_type VARCHAR(16),
                    created_at TIMESTAMP,
                    updated_at TIMESTAMP
                )
                """);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(MessageMapper.class);
        session = factory.openSession(true);
        messages = session.getMapper(MessageMapper.class);
    }

    @AfterEach
    void cleanup() {
        if (session != null) {
            session.close();
        }
    }

    private Message forwardMessage() {
        Message msg = new Message();
        msg.setId("m1");
        msg.setConversationId("c1");
        msg.setSenderId(7L);
        msg.setContentType(ChatContentTypeEnum.FORWARD_CONTENT);
        msg.setSeq(1L);
        msg.setStatus(MessageStatusEnum.SENT);
        msg.setCreatedAt(LocalDateTime.now());
        ChatContent content = new ChatContent();
        content.setContentId(99L);
        content.setMediaType("dynamic");
        content.setTitle("真实标题");
        content.setCoverObjectKey("chosen-cover");
        msg.setContent(content);
        return msg;
    }

    @Test
    void forwardCoverKeyIsPersistedAndReadBack() {
        messages.insert(forwardMessage());

        // 原始 JSON 列里必须带 key: 只要写入端把字段跳过, 后面读什么都救不回来
        String rawJson = jdbc.queryForObject("SELECT content FROM message WHERE id = 'm1'", String.class);
        assertTrue(rawJson.contains("\"coverObjectKey\":\"chosen-cover\""), "落库 JSON 缺少封面 key: " + rawJson);

        Message stored = messages.selectById("m1");
        assertEquals("chosen-cover", stored.getContent().getCoverObjectKey());
        assertEquals("真实标题", stored.getContent().getTitle());
    }
}
