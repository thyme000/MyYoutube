package cn.diaojiahong.myyoutube.user;

import cn.diaojiahong.myyoutube.common.filter.UserIdHeaderFilter;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.cache.UserSummaryCache;
import cn.diaojiahong.myyoutube.user.controller.UserController;
import cn.diaojiahong.myyoutube.user.mapper.UserMapper;
import cn.diaojiahong.myyoutube.user.service.impl.UserServiceImpl;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserProfileAccessIntegrationTest {
    private final UserSummaryCache summaryCache = mock(UserSummaryCache.class);

    private final EsSyncPublisher publisher = mock(EsSyncPublisher.class);
    private JdbcTemplate jdbc;
    private SqlSession session;
    private MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:profile-access;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS user");
        jdbc.execute("""
                CREATE TABLE user (
                    id BIGINT PRIMARY KEY, username VARCHAR(64), password VARCHAR(128),
                    role VARCHAR(16) NOT NULL DEFAULT 'USER',
                    avatar_url VARCHAR(255), email VARCHAR(128), phone VARCHAR(32),
                    nickname VARCHAR(64), create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
                    is_delete INT DEFAULT 0, fans BIGINT DEFAULT 0, attention_count BIGINT DEFAULT 0,
                    content_count BIGINT DEFAULT 0, introduction VARCHAR(255), bg_image VARCHAR(255),
                    like_count BIGINT DEFAULT 0, like_version BIGINT NOT NULL DEFAULT 0
                )
                """);
        jdbc.update("""
                INSERT INTO user(id, nickname, introduction, create_time) VALUES
                (1, 'Alice', 'Alice bio', '2024-01-01 00:15:00'),
                (2, 'Bob', 'Bob bio', '2023-12-31 23:45:00')
                """);

        var factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(UserMapper.class);
        session = factory.openSession(true);
        var service = new UserServiceImpl(mock(S3StorageUtil.class), publisher, summaryCache);
        ReflectionTestUtils.setField(service, "baseMapper", session.getMapper(UserMapper.class));

        var filter = new UserIdHeaderFilter(new ObjectMapper());
        ReflectionTestUtils.setField(filter, "internalToken", "profile-test-token");
        mvc = MockMvcBuilders.standaloneSetup(new UserController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
                .build();
    }

    @AfterEach
    void cleanup() {
        if (session != null) {
            session.close();
        }
        UserContext.removeUser();
    }

    @Test
    void deletedUserProfileIsNotFound() throws Exception {
        jdbc.update("UPDATE user SET is_delete = 1 WHERE id = 1");

        mvc.perform(get("/user/info/1")
                        .header(UserIdHeaderFilter.USER_ID_HEADER, "2")
                        .header(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, "profile-test-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("用户不存在"));
    }

    @Test
    void currentProfileReturnsTheRegistrationYearForTheAuthenticatedUser() throws Exception {
        mvc.perform(get("/user/info").param("userId", "2")
                        .header(UserIdHeaderFilter.USER_ID_HEADER, "1")
                        .header(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, "profile-test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.nickname").value("Alice"))
                .andExpect(jsonPath("$.data.registeredYear").value(2024));

        mvc.perform(get("/user/info/1")
                        .header(UserIdHeaderFilter.USER_ID_HEADER, "2")
                        .header(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, "profile-test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registeredYear").doesNotHaveJsonPath());
    }

    @Test
    void updatesOnlyCurrentUserAndPublishesTheirId() throws Exception {
        mvc.perform(put("/user/info")
                        .header(UserIdHeaderFilter.USER_ID_HEADER, "1")
                        .header(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, "profile-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"Alice updated","introduction":"New bio"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("修改成功"));

        assertProfile(1L, "Alice updated", "New bio");
        assertProfile(2L, "Bob", "Bob bio");
        verify(summaryCache).evict(1L);
        verify(publisher).syncUserDoc(1L);
        verifyNoMoreInteractions(summaryCache, publisher);
        assertNull(UserContext.getUserId());
    }

    @Test
    void bodyCannotSelectAnotherUserOrChangeAvatar() throws Exception {
        mvc.perform(put("/user/info")
                        .header(UserIdHeaderFilter.USER_ID_HEADER, "1")
                        .header(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, "profile-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":2,"nickname":"Changed","introduction":"Changed bio","avatarUrl":"injected"}
                                """))
                .andExpect(status().isOk());

        assertProfile(1L, "Changed", "Changed bio");
        assertProfile(2L, "Bob", "Bob bio");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE avatar_url IS NOT NULL", Integer.class));
        verify(summaryCache).evict(1L);
        verify(publisher).syncUserDoc(1L);
        verifyNoMoreInteractions(summaryCache, publisher);
    }

    @Test
    void oldPathCannotUpdateAnotherUser() throws Exception {
        mvc.perform(put("/user/2")
                        .header(UserIdHeaderFilter.USER_ID_HEADER, "1")
                        .header(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, "profile-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Attacker\"}"))
                .andExpect(status().isNotFound());

        assertProfilesUnchanged();
    }

    @Test
    void unauthenticatedUpdateIsRejected() throws Exception {
        mvc.perform(put("/user/info")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Attacker\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        assertProfilesUnchanged();
    }

    @Test
    void missingCurrentUserCannotFallBackToBodyId() throws Exception {
        mvc.perform(put("/user/info")
                        .header(UserIdHeaderFilter.USER_ID_HEADER, "3")
                        .header(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, "profile-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":2,\"nickname\":\"Attacker\"}"))
                .andExpect(status().isNotFound());

        assertProfilesUnchanged();
    }

    private void assertProfilesUnchanged() {
        assertProfile(1L, "Alice", "Alice bio");
        assertProfile(2L, "Bob", "Bob bio");
        verifyNoInteractions(summaryCache, publisher);
    }

    private void assertProfile(Long id, String nickname, String introduction) {
        assertEquals(nickname, jdbc.queryForObject("SELECT nickname FROM user WHERE id = ?", String.class, id));
        assertEquals(introduction, jdbc.queryForObject("SELECT introduction FROM user WHERE id = ?", String.class, id));
    }
}
