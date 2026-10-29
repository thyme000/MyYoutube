package cn.diaojiahong.myyoutube.user;

import cn.diaojiahong.myyoutube.common.security.InternalApiCredentials;

import cn.diaojiahong.myyoutube.auth.controller.AuthController;
import cn.diaojiahong.myyoutube.auth.domain.dto.ForgetPasswordDTO;
import cn.diaojiahong.myyoutube.auth.service.impl.AuthServiceImpl;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.filter.UserIdHeaderFilter;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.PasswordEncoder;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.security.AuthConstants;
import cn.diaojiahong.myyoutube.user.api.enums.UserRole;
import cn.diaojiahong.myyoutube.user.cache.UserSummaryCache;
import cn.diaojiahong.myyoutube.user.controller.InternalUserRoleController;
import cn.diaojiahong.myyoutube.user.controller.CurrentUserRoleController;
import cn.diaojiahong.myyoutube.user.controller.UserController;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.mapper.UserMapper;
import cn.diaojiahong.myyoutube.user.port.UserRolePortAdapter;
import cn.diaojiahong.myyoutube.user.service.impl.UserServiceImpl;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserRoleIntegrationTest {
    private static final String INTERNAL_TOKEN = "role-test-token";

    private final UserSummaryCache summaryCache = mock(UserSummaryCache.class);
    private final EsSyncPublisher publisher = mock(EsSyncPublisher.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private JdbcTemplate jdbc;
    private UserMapper mapper;
    private UserRolePortAdapter roles;
    private UserServiceImpl users;
    private MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:user-role-" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        String schema = Files.readString(Path.of("../../resources/schema.sql"));
        String userSchema = schema.substring(schema.indexOf("CREATE TABLE IF NOT EXISTS `user`"),
                schema.indexOf("-- ==================== video_db"));
        new ResourceDatabasePopulator(new ByteArrayResource(userSchema.getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);

        var factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(UserMapper.class);
        mapper = new SqlSessionTemplate(factory).getMapper(UserMapper.class);
        roles = new UserRolePortAdapter(mapper);

        users = spy(new UserServiceImpl(mock(S3StorageUtil.class), publisher, summaryCache));
        ReflectionTestUtils.setField(users, "baseMapper", mapper);
        mvc = MockMvcBuilders.standaloneSetup(new InternalUserRoleController(roles, new InternalApiCredentials(INTERNAL_TOKEN)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
        if (jdbc != null) {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void schemaInitializesThymeAsSuperAdminAndOtherUsersAsOrdinaryUsers() {
        assertEquals("thyme", mapper.selectById(1L).getUsername());
        assertEquals(UserRole.SUPER_ADMIN, roles.getRole(1L));
        assertEquals(UserRole.USER, roles.getRole(2L));

        mapper.insert(new User().setId(3L).setUsername("default-role").setPassword("hash"));
        assertEquals(UserRole.USER, roles.getRole(3L));
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void enumPersistsAndInternalApiReturnsTheSameCode(UserRole role) throws Exception {
        mapper.insert(new User().setId(3L).setUsername("role-user").setPassword("hash").setRole(role));
        assertEquals(role.name(), jdbc.queryForObject("SELECT role FROM user WHERE id = 3", String.class));

        mvc.perform(get("/internal/user/roles/3").header(AuthConstants.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().json('"' + role.name() + '"'));
    }

    @Test
    void nextLookupReadsRoleChangesWithoutCacheOrAnIdOneBackdoor() {
        assertEquals(UserRole.SUPER_ADMIN, roles.getRole(1L));
        jdbc.update("UPDATE user SET role = 'USER' WHERE id = 1");
        assertEquals(UserRole.USER, roles.getRole(1L));

        jdbc.update("UPDATE user SET role = 'ADMIN' WHERE id = 2");
        assertEquals(UserRole.ADMIN, roles.getRole(2L));
        jdbc.update("UPDATE user SET role = 'USER' WHERE id = 2");
        assertEquals(UserRole.USER, roles.getRole(2L));
        verifyNoInteractions(summaryCache, redis);
    }

    @Test
    void missingAndDeletedUsersAreRejected() throws Exception {
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class, () -> roles.getRole(99L)).getErrorCode());
        jdbc.update("UPDATE user SET is_delete = 1 WHERE id = 1");
        mvc.perform(get("/internal/user/roles/1").header(AuthConstants.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    void invalidDatabaseRoleCannotFallBackToAPrivilegedRole() {
        jdbc.update("UPDATE user SET role = 'ROOT' WHERE id = 1");
        assertThrows(RuntimeException.class, () -> roles.getRole(1L));
    }

    @Test
    void roleLookupRequiresInternalCredentialsEvenWithoutAUserHeader() throws Exception {
        mvc.perform(get("/internal/user/roles/1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/user/roles/1").header(AuthConstants.INTERNAL_TOKEN_HEADER, "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/user/roles/1").header(AuthConstants.USER_ID_HEADER, "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnconfiguredInternalSecretDisablesSourceValidation() throws Exception {
        MockMvc unconfigured = MockMvcBuilders.standaloneSetup(new InternalUserRoleController(roles, new InternalApiCredentials("")))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        unconfigured.perform(get("/internal/user/roles/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("SUPER_ADMIN"));
    }

    @Test
    void currentRoleEndpointReturnsOnlyTheAuthenticatedAccountsRole() throws Exception {
        currentRoleMvc().perform(get("/user/role").param("userId", "1")
                        .header(AuthConstants.USER_ID_HEADER, "2")
                        .header(AuthConstants.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data").value("USER"));
    }

    @Test
    void currentRoleEndpointRequiresATrustedLoggedInIdentity() throws Exception {
        MockMvc currentRole = currentRoleMvc();
        currentRole.perform(get("/user/role"))
                .andExpect(status().isUnauthorized());
        currentRole.perform(get("/user/role").header(AuthConstants.USER_ID_HEADER, "1")
                        .header(AuthConstants.INTERNAL_TOKEN_HEADER, "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void currentRoleEndpointReadsTheNextDatabaseRoleWithoutRefreshingTokens() throws Exception {
        MockMvc currentRole = currentRoleMvc();
        for (UserRole role : UserRole.values()) {
            jdbc.update("UPDATE user SET role = ? WHERE id = 2", role.name());
            currentRole.perform(get("/user/role")
                            .header(AuthConstants.USER_ID_HEADER, "2")
                            .header(AuthConstants.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(role.name()));
        }
        verifyNoInteractions(summaryCache, redis);
    }

    @Test
    void registrationCannotAcceptAnInjectedRoleOrUserId() throws Exception {
        MockMvc registration = MockMvcBuilders.standaloneSetup(new AuthController(auth()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        registration.perform(post("/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"username":"newbie","password":"Password1234","confirmPassword":"Password1234",
                         "id":1,"role":"SUPER_ADMIN"}
                        """))
                .andExpect(status().isOk());

        Long newId = jdbc.queryForObject("SELECT id FROM user WHERE username = 'newbie'", Long.class);
        assertNotEquals(1L, newId);
        assertEquals(UserRole.USER, roles.getRole(newId));
        assertEquals(UserRole.SUPER_ADMIN, roles.getRole(1L));
        verify(publisher).syncUserDoc(newId);
    }

    @Test
    void profileBodyCannotGrantRolesOrSelectAnotherAccount() throws Exception {
        profileMvc().perform(put("/user/info")
                        .header(AuthConstants.USER_ID_HEADER, "2")
                        .header(AuthConstants.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":1,\"role\":\"SUPER_ADMIN\",\"nickname\":\"Updated\"}"))
                .andExpect(status().isOk());

        assertEquals(UserRole.USER, roles.getRole(2L));
        assertEquals(UserRole.SUPER_ADMIN, roles.getRole(1L));
        assertEquals("Updated", mapper.selectById(2L).getNickname());
        assertEquals("thyme", mapper.selectById(1L).getNickname());
    }

    @Test
    void profileWriteCannotRestoreARoleRevokedAfterItsRead() throws Exception {
        doAnswer(call -> {
            User snapshot = (User) call.callRealMethod();
            jdbc.update("UPDATE user SET role = 'USER' WHERE id = 1");
            return snapshot;
        }).when(users).getById(1L);

        profileMvc().perform(put("/user/info")
                        .header(AuthConstants.USER_ID_HEADER, "1")
                        .header(AuthConstants.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"Updated\"}"))
                .andExpect(status().isOk());

        assertEquals(UserRole.USER, roles.getRole(1L));
        assertEquals("Updated", mapper.selectById(1L).getNickname());
    }

    @Test
    void profileUpdateReturnsNotFoundWhenTheUserDisappearsAfterItsRead() throws Exception {
        doAnswer(call -> {
            User snapshot = (User) call.callRealMethod();
            jdbc.update("DELETE FROM user WHERE id = 1");
            return snapshot;
        }).when(users).getById(1L);

        profileMvc().perform(put("/user/info")
                        .header(AuthConstants.USER_ID_HEADER, "1")
                        .header(AuthConstants.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"Updated\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.message").value("用户不存在或已被删除"));

        verifyNoInteractions(summaryCache, publisher);
    }

    @Test
    void passwordResetCannotRestoreARoleRevokedAfterItsRead() {
        jdbc.update("UPDATE user SET phone = '13800000000' WHERE id = 1");
        var query = new LambdaQueryChainWrapper<User>(mapper) {
            @Override
            public User one() {
                User snapshot = super.one();
                jdbc.update("UPDATE user SET role = 'USER' WHERE id = 1");
                return snapshot;
            }
        };
        doReturn(query).when(users).lambdaQuery();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var request = new ForgetPasswordDTO();
        request.setUsername("thyme");
        request.setPhone("13800000000");
        request.setNewPassword("Changed1234");

        auth().forgetPassword(request);

        assertEquals(UserRole.USER, roles.getRole(1L));
        assertTrue(PasswordEncoder.matches("Changed1234", mapper.selectById(1L).getPassword()));
        verify(values).set(eq(RedisConstants.USER_TOKEN_VER + 1L), anyString(),
                eq(RedisConstants.USER_REFRESH_TOKEN_EXPIRE), eq(TimeUnit.HOURS));
    }

    private AuthServiceImpl auth() {
        return new AuthServiceImpl(users, redis, mock(RocketMQTemplate.class), publisher,
                mock(SyncChainStepRegistry.class));
    }

    private MockMvc profileMvc() {
        var filter = new UserIdHeaderFilter(new ObjectMapper());
        ReflectionTestUtils.setField(filter, "internalToken", INTERNAL_TOKEN);
        return MockMvcBuilders.standaloneSetup(new UserController(users))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(filter).build();
    }

    private MockMvc currentRoleMvc() {
        var filter = new UserIdHeaderFilter(new ObjectMapper());
        ReflectionTestUtils.setField(filter, "internalToken", INTERNAL_TOKEN);
        return MockMvcBuilders.standaloneSetup(new CurrentUserRoleController(roles))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(filter).build();
    }
}
