package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.comment.service.IContentCommentService;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.service.ContentAccessService;
import cn.diaojiahong.myyoutube.notification.domain.vo.NotificationVO;
import cn.diaojiahong.myyoutube.notification.mapper.UserNotificationMapper;
import cn.diaojiahong.myyoutube.notification.service.impl.UserNotificationServiceImpl;
import cn.diaojiahong.myyoutube.notification.support.ReviewFeedback;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import javax.sql.DataSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(ReviewNotificationIntegrationTest.Config.class)
class ReviewNotificationIntegrationTest {
    @Configuration
    @MapperScan(basePackageClasses = UserNotificationMapper.class)
    static class Config {
        @Bean
        DataSource dataSource() {
            return ContentTestDatabase.create("review-notifications");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var bean = new MybatisSqlSessionFactoryBean();
            bean.setDataSource(ds);
            var plugins = new MybatisPlusInterceptor();
            plugins.addInnerInterceptor(new PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.H2));
            bean.setPlugins(plugins);
            return bean.getObject();
        }

        @Bean
        ContentAccessService access() {
            return mock(ContentAccessService.class);
        }

        @Bean
        UserPort users() {
            return mock(UserPort.class);
        }

        @Bean
        IContentCommentService comments() {
            return mock(IContentCommentService.class);
        }

        @Bean
        UserNotificationServiceImpl service(
                ContentAccessService access,
                UserPort users,
                IContentCommentService comments) {
            return new UserNotificationServiceImpl(access, users, comments);
        }
    }

    @Autowired DataSource database;
    @Autowired UserNotificationServiceImpl service;
    @Autowired ContentAccessService access;
    @Autowired UserPort users;
    JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        reset(access, users);
        UserContext.setUser("9");
        jdbc = new JdbcTemplate(database);
        jdbc.execute("DROP TABLE IF EXISTS user_notification");
        jdbc.execute("CREATE TABLE user_notification(id BIGINT AUTO_INCREMENT PRIMARY KEY,receiver_id BIGINT,sender_id BIGINT,type VARCHAR(32),title VARCHAR(100),content TEXT,ref_type VARCHAR(32),ref_id BIGINT,extra_json TEXT,is_read INT,create_time TIMESTAMP)");
        when(access.readable(anyLong(), anyLong())).thenThrow(new BizException(ErrorCode.NOT_FOUND, "内容不可访问"));
        when(users.listByIds(anyCollection())).thenReturn(List.of());
    }

    @AfterEach
    void clear() {
        UserContext.removeUser();
    }

    void notice(String type, String title, String content, String refType) {
        jdbc.update("INSERT INTO user_notification(receiver_id,type,title,content,ref_type,ref_id,is_read,create_time) VALUES(9,?,?,?,?,1,0,CURRENT_TIMESTAMP)",
                type, title, content, refType);
    }

    @Test
    void historicalReviewRemarksAreReplacedOnReadWithoutChangingStoredText() {
        String internal = "模型识别失败，人工完整试听及封面核验通过";
        notice("SYSTEM_NOTICE", "内容审核通过", internal, "CONTENT");
        notice("SYSTEM_NOTICE", "内容审核未通过", "内部证据与处置备注", "CONTENT");
        List<NotificationVO> list = service.getNotificationList(new PageQuery()).getList();
        assertEquals(2, list.size());
        assertTrue(list.stream().anyMatch(n -> "您的内容已通过审核。".equals(n.getContent())));
        assertTrue(list.stream().anyMatch(n -> "您的内容未通过审核，已限制展示。".equals(n.getContent())));
        assertTrue(list.stream().allMatch(n -> n.getContentId() == null));
        assertEquals(
                internal,
                jdbc.queryForObject("SELECT content FROM user_notification WHERE title='内容审核通过'", String.class));
    }

    @Test
    void unrelatedSystemAndCommentNotificationsKeepTheirOwnMessages() {
        notice("SYSTEM_NOTICE", "设置提醒", "原有系统提醒", "CONTENT");
        notice("COMMENT_REPLY", "内容审核通过", "原有回复正文", "COMMENT");
        List<NotificationVO> list = service.getNotificationList(new PageQuery()).getList();
        assertTrue(list.stream().anyMatch(n -> "原有系统提醒".equals(n.getContent())));
        assertTrue(list.stream().anyMatch(n -> "原有回复正文".equals(n.getContent())));
    }

    @Test
    void audioBanNotificationUsesFixedFeedbackEvenIfStoredTextContainsInternalDetails() {
        String internal = "关联音频已被封禁，audioId=20，内部人工核验记录";
        notice("SYSTEM_NOTICE", ReviewFeedback.AUDIO_BANNED_TITLE, internal, "CONTENT");
        NotificationVO result = service.getNotificationList(new PageQuery()).getList().get(0);
        assertEquals(ReviewFeedback.audioBannedMessage(), result.getContent());
        assertNull(result.getContentId());
        assertEquals(internal, jdbc.queryForObject("SELECT content FROM user_notification", String.class));
    }
}
