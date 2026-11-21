package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.domain.enums.InviteStatusEnum;
import cn.diaojiahong.myyoutube.chat.domain.vo.ConversationInviteVO;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationInviteMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.api.NotificationPort;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 邀请列表的查询语义在真实 SQL 上验证：
 * 默认返回全部状态（含已解散群的待处理邀请）、按时间倒序、
 * 待处理筛选拿到且只拿到 PENDING、只看得到发给自己（invitee_id）的邀请。
 */
class ConversationInviteListSqlTest {

    private static final Long INVITER = 11L;
    private static final Long INVITEE = 22L;
    private static final Long OTHER_INVITEE = 33L;

    private JdbcTemplate jdbc;
    private SqlSession session;
    private ConversationInviteService service;
    private UserPort userPort;

    @BeforeEach
    void setup() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:conversation-invite-list;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS conversation");
        jdbc.execute("DROP TABLE IF EXISTS conversation_invite");
        jdbc.execute("""
                CREATE TABLE conversation (
                    id VARCHAR(32) PRIMARY KEY,
                    type VARCHAR(16) NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    name VARCHAR(32),
                    avatar VARCHAR(512),
                    last_seq BIGINT NOT NULL DEFAULT 0,
                    created_by BIGINT,
                    user_a BIGINT,
                    user_b BIGINT,
                    banned TINYINT,
                    announcement VARCHAR(2000),
                    announcement_version INT,
                    created_at DATETIME,
                    updated_at DATETIME
                )
                """);
        jdbc.execute("""
                CREATE TABLE conversation_invite (
                    id VARCHAR(32) PRIMARY KEY,
                    conversation_id VARCHAR(32) NOT NULL,
                    inviter_id BIGINT NOT NULL,
                    invitee_id BIGINT NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    created_at DATETIME,
                    updated_at DATETIME,
                    CONSTRAINT uk_conversation_invite UNIQUE (conversation_id, invitee_id)
                )
                """);
        jdbc.update("INSERT INTO conversation(id, type, status, name, last_seq) VALUES"
                + "('g1', 'GROUP', 'ACTIVE', '周末局', 0),"
                + "('g2', 'GROUP', 'ACTIVE', '打球群', 0),"
                + "('g3', 'GROUP', 'CLOSED', '已解散的群', 0)");
        jdbc.update("INSERT INTO conversation_invite"
                + "(id, conversation_id, inviter_id, invitee_id, status, created_at, updated_at) VALUES"
                + "('i-newest', 'g1', 11, 22, 'PENDING',  '2026-11-20 10:00:00', '2026-11-20 10:00:00'),"
                + "('i-accepted', 'g2', 11, 22, 'ACCEPTED', '2026-11-19 10:00:00', '2026-11-19 10:00:00'),"
                + "('i-dissolved', 'g3', 11, 22, 'PENDING', '2026-11-18 10:00:00', '2026-11-18 10:00:00'),"
                + "('i-others', 'g1', 44, 33, 'PENDING', '2026-11-17 10:00:00', '2026-11-17 10:00:00')");

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new ClassPathResource("mapper/ConversationMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        // 纯 MyBatis 环境没有 @MapperScan: 无 XML 的 BaseMapper 要手动注册才会被注入 CRUD
        factory.getConfiguration().addMapper(ConversationInviteMapper.class);
        session = factory.openSession(true);
        ConversationMapper conversations = session.getMapper(ConversationMapper.class);
        ConversationInviteMapper invites = session.getMapper(ConversationInviteMapper.class);

        UserSummary inviter = new UserSummary();
        inviter.setId(INVITER);
        inviter.setNickname("邀请人");
        userPort = mock(UserPort.class);
        when(userPort.listByIds(ArgumentMatchers.anyList())).thenReturn(List.of(inviter));

        ConversationService conversationService = new ConversationService(
                conversations, mock(ConversationMemberMapper.class), mock(PrivateConversationService.class),
                mock(FollowPort.class), userPort,
                mock(S3StorageUtil.class), mock(RocketMQTemplate.class),
                mock(PushFanoutPublisher.class), mock(TransactionTemplate.class));
        service = new ConversationInviteService(
                conversations, mock(ConversationMemberMapper.class), invites, conversationService,
                mock(FollowPort.class), userPort, mock(PushFanoutPublisher.class),
                mock(NotificationPort.class), mock(TransactionTemplate.class));
    }

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
        if (session != null) {
            session.close();
        }
    }

    @Test
    void withoutFilterReturnsEveryStatusNewestFirst() {
        UserContext.setUser(String.valueOf(INVITEE));

        List<ConversationInviteVO> vos = service.listInvites(null);

        assertEquals(3, vos.size());
        assertEquals("i-newest", vos.get(0).getId());
        assertEquals(InviteStatusEnum.PENDING, vos.get(0).getStatus());
        assertEquals("周末局", vos.get(0).getConversationName());
        assertEquals("i-accepted", vos.get(1).getId());
        assertEquals(InviteStatusEnum.ACCEPTED, vos.get(1).getStatus());
        // 已解散群的待处理邀请同样返回
        assertEquals("i-dissolved", vos.get(2).getId());
        assertEquals(InviteStatusEnum.PENDING, vos.get(2).getStatus());
        assertEquals("已解散的群", vos.get(2).getConversationName());
        assertEquals(INVITER, vos.get(0).getInviterId());
        assertEquals("邀请人", vos.get(0).getInviterNickname());
    }

    @Test
    void pendingFilterReturnsOnlyPendingOnes() {
        UserContext.setUser(String.valueOf(INVITEE));

        List<ConversationInviteVO> vos = service.listInvites(InviteStatusEnum.PENDING.name());

        assertEquals(2, vos.size());
        assertEquals("i-newest", vos.get(0).getId());
        assertEquals("i-dissolved", vos.get(1).getId());
    }

    @Test
    void onlySeesInvitesAddressedToMe() {
        UserContext.setUser(String.valueOf(OTHER_INVITEE));

        List<ConversationInviteVO> vos = service.listInvites(null);

        assertEquals(1, vos.size());
        assertEquals("i-others", vos.get(0).getId());
    }

    @Test
    void invalidStatusCodeIsRejected() {
        UserContext.setUser(String.valueOf(INVITEE));

        BizException error = assertThrows(BizException.class, () -> service.listInvites("NO_SUCH_STATUS"));

        assertEquals("无效的邀请状态: NO_SUCH_STATUS", error.getMessage());
    }
}
