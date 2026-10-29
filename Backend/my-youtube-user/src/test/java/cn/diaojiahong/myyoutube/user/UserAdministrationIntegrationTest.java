package cn.diaojiahong.myyoutube.user;

import cn.diaojiahong.myyoutube.common.config.MybatisPlusConfig;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.feign.InternalFeignConfiguration;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.security.InternalApiCredentials;
import cn.diaojiahong.myyoutube.security.AuthConstants;
import cn.diaojiahong.myyoutube.user.api.UserAdministrationPort;
import cn.diaojiahong.myyoutube.user.api.UserAdministrationPort.*;
import cn.diaojiahong.myyoutube.user.api.enums.UserRole;
import cn.diaojiahong.myyoutube.user.controller.InternalUserAdministrationController;
import cn.diaojiahong.myyoutube.user.domain.po.UserRoleChange;
import cn.diaojiahong.myyoutube.user.mapper.UserMapper;
import cn.diaojiahong.myyoutube.user.mapper.UserRoleChangeMapper;
import cn.diaojiahong.myyoutube.user.port.UserRolePortAdapter;
import cn.diaojiahong.myyoutube.user.service.UserAdministrationService;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import feign.Client;
import feign.Response;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserAdministrationIntegrationTest {
    private JdbcTemplate jdbc;
    private UserAdministrationService service;
    private UserRoleChangeMapper changes;
    private UserRolePortAdapter roles;
    private MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:user-administration-" + UUID.randomUUID()
                        + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(source);
        String schema = Files.readString(Path.of("../../resources/schema.sql"));
        String userSchema = schema.substring(schema.indexOf("CREATE TABLE IF NOT EXISTS `user`"),
                schema.indexOf("-- ==================== video_db"));
        new ResourceDatabasePopulator(new ByteArrayResource(userSchema.getBytes(StandardCharsets.UTF_8)))
                .execute(source);
        jdbc.update("""
                INSERT INTO user(id, username, password, nickname, role, is_delete) VALUES
                (3, 'super-two', 'hash', '超级管理员二', 'SUPER_ADMIN', 0),
                (4, 'moderator', 'hash', '管理员', 'ADMIN', 0),
                (5, 'ordinary', 'hash', '普通用户', 'USER', 0),
                (6, 'removed', 'hash', '已删除管理员', 'ADMIN', 1)
                """);

        var factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(source);
        factoryBean.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor());
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(UserMapper.class);
        factory.getConfiguration().addMapper(UserRoleChangeMapper.class);
        var template = new SqlSessionTemplate(factory);
        UserMapper users = template.getMapper(UserMapper.class);
        changes = template.getMapper(UserRoleChangeMapper.class);
        roles = new UserRolePortAdapter(users);
        var target = new UserAdministrationService(users, changes);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (UserAdministrationService) proxy.getProxy();
        mvc = MockMvcBuilders.standaloneSetup(new InternalUserAdministrationController(
                        service, new InternalApiCredentials("administration-test-token")))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void cleanup() {
        if (jdbc != null) {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void listContainsOnlyActiveAdministratorsAndKeepsPageTotals() {
        PageResult<Administrator> first = service.page(new Query(1L, 1, 2));
        assertEquals(3, first.getTotal());
        assertEquals(2, first.getPages());
        assertEquals(List.of(4L, 3L), first.getList().stream().map(Administrator::userId).toList());
        assertEquals(UserRole.ADMIN, first.getList().get(0).role());
        assertEquals("moderator", first.getList().get(0).username());

        PageResult<Administrator> second = service.page(new Query(3L, 2, 2));
        assertEquals(List.of(1L), second.getList().stream().map(Administrator::userId).toList());
        PageResult<Administrator> empty = service.page(new Query(1L, 3, 2));
        assertEquals(3, empty.getTotal());
        assertEquals(2, empty.getPages());
        assertTrue(empty.getList().isEmpty());
    }

    @Test
    void grantAndRevokeAreVisibleImmediatelyAndRecordedWithTheRealActor() {
        assertEquals(new Change(2L, UserRole.ADMIN, true), service.grant(2L, 3L));
        assertEquals(UserRole.ADMIN, roles.getRole(2L));
        assertEquals(new Change(2L, UserRole.USER, true), service.revoke(2L, 3L));
        assertEquals(UserRole.USER, roles.getRole(2L));

        List<UserRoleChange> history = changes.selectList(null);
        history.sort(java.util.Comparator.comparing(UserRoleChange::getId));
        assertEquals(2, history.size());
        assertEquals(3L, history.get(0).getActorId());
        assertEquals(2L, history.get(0).getTargetUserId());
        assertEquals(UserRole.USER, history.get(0).getPreviousRole());
        assertEquals(UserRole.ADMIN, history.get(0).getNewRole());
        assertEquals(UserRole.ADMIN, history.get(1).getPreviousRole());
        assertEquals(UserRole.USER, history.get(1).getNewRole());
        assertNotNull(history.get(0).getCreateTime());
    }

    @Test
    void repeatedCommandsAreIdempotentAndDoNotDuplicateHistory() {
        assertFalse(service.revoke(2L, 1L).changed());
        assertTrue(service.grant(2L, 1L).changed());
        assertFalse(service.grant(2L, 1L).changed());
        assertEquals(1, auditCount());
        assertTrue(service.revoke(2L, 1L).changed());
        assertFalse(service.revoke(2L, 1L).changed());
        assertEquals(2, auditCount());
    }

    @ParameterizedTest
    @ValueSource(longs = {2, 4, 6, 99})
    void ordinaryMissingOrDeletedActorsCannotManageAdministrators(long actorId) {
        assertEquals(ErrorCode.SUPER_ADMIN_REQUIRED,
                assertThrows(BizException.class, () -> service.page(new Query(actorId, 1, 10))).getErrorCode());
        assertEquals(ErrorCode.SUPER_ADMIN_REQUIRED,
                assertThrows(BizException.class, () -> service.grant(5L, actorId)).getErrorCode());
        assertEquals(ErrorCode.SUPER_ADMIN_REQUIRED,
                assertThrows(BizException.class, () -> service.revoke(4L, actorId)).getErrorCode());
        assertEquals(UserRole.USER, roles.getRole(5L));
        assertEquals(UserRole.ADMIN, roles.getRole(4L));
        assertEquals(0, auditCount());
    }

    @Test
    void actorPrivilegesAreRecheckedInTheDatabaseRatherThanTrustingAnEarlierCheck() {
        assertEquals(UserRole.SUPER_ADMIN, roles.getRole(1L));
        jdbc.update("UPDATE user SET role = 'ADMIN' WHERE id = 1");
        assertEquals(ErrorCode.SUPER_ADMIN_REQUIRED,
                assertThrows(BizException.class, () -> service.grant(2L, 1L)).getErrorCode());
        assertEquals(UserRole.USER, roles.getRole(2L));
        assertEquals(0, auditCount());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3})
    void superAdministratorsCannotBeGrantedOrRevokedThroughTheseEndpoints(long targetId) {
        assertEquals(ErrorCode.STATE_CONFLICT,
                assertThrows(BizException.class, () -> service.grant(targetId, 1L)).getErrorCode());
        assertEquals(ErrorCode.STATE_CONFLICT,
                assertThrows(BizException.class, () -> service.revoke(targetId, 1L)).getErrorCode());
        assertEquals(UserRole.SUPER_ADMIN, roles.getRole(targetId));
        assertEquals(0, auditCount());
    }

    @ParameterizedTest
    @ValueSource(longs = {6, 99})
    void missingOrDeletedTargetsCannotBeModified(long targetId) {
        assertEquals(ErrorCode.NOT_FOUND,
                assertThrows(BizException.class, () -> service.grant(targetId, 1L)).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND,
                assertThrows(BizException.class, () -> service.revoke(targetId, 1L)).getErrorCode());
        assertEquals(0, auditCount());
    }

    @Test
    void roleUpdatesLeaveProfileAndPasswordUntouched() {
        String password = jdbc.queryForObject("SELECT password FROM user WHERE id = 2", String.class);
        service.grant(2L, 1L);
        assertEquals(password, jdbc.queryForObject("SELECT password FROM user WHERE id = 2", String.class));
        assertEquals("test", jdbc.queryForObject("SELECT username FROM user WHERE id = 2", String.class));
        assertEquals("test", jdbc.queryForObject("SELECT nickname FROM user WHERE id = 2", String.class));
    }

    @Test
    void auditFailureRollsBackTheRoleChange() {
        jdbc.execute("ALTER TABLE user_role_change ADD CONSTRAINT reject_audit CHECK(actor_id <> 1)");
        assertThrows(RuntimeException.class, () -> service.grant(2L, 1L));
        assertEquals(UserRole.USER, roles.getRole(2L));
        assertEquals(0, auditCount());
    }

    @Test
    void concurrentGrantsFromDifferentSuperAdministratorsProduceOneChange() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<Change> first = executor.submit(() -> { start.await(); return service.grant(2L, 1L); });
            Future<Change> second = executor.submit(() -> { start.await(); return service.grant(2L, 3L); });
            start.countDown();
            List<Change> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(Change::changed).count());
            assertEquals(1, auditCount());
            assertEquals(UserRole.ADMIN, roles.getRole(2L));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void invalidPaginationAndTargetIdsAreRejectedWithoutChanges() {
        assertEquals(ErrorCode.INVALID_ARGUMENT,
                assertThrows(BizException.class, () -> service.page(new Query(1L, 0, 10))).getErrorCode());
        assertEquals(ErrorCode.INVALID_ARGUMENT,
                assertThrows(BizException.class, () -> service.page(new Query(1L, 1, 101))).getErrorCode());
        assertEquals(ErrorCode.INVALID_ARGUMENT,
                assertThrows(BizException.class, () -> service.grant(0L, 1L)).getErrorCode());
        assertEquals(0, auditCount());
    }

    @Test
    void internalEndpointsRequireCredentialsAndRecheckTheActor() throws Exception {
        mvc.perform(post("/internal/user/administrators/page").contentType("application/json")
                        .content("{\"actorId\":1,\"pageNo\":1,\"pageSize\":10}"))
                .andExpect(status().isUnauthorized());
        for (String method : List.of("PUT", "DELETE")) {
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/internal/user/administrators/2")
                            .param("actorId", "1").header(AuthConstants.INTERNAL_TOKEN_HEADER, "wrong"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/internal/user/administrators/2")
                            .param("actorId", "4").header(AuthConstants.INTERNAL_TOKEN_HEADER, "administration-test-token"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40302));
        }
        assertEquals(0, auditCount());
    }

    @Test
    void springFeignContractExecutesTransactionsAndPreservesBusinessErrors() {
        runner("administration-test-token").run(context -> {
            assertThat(context).hasNotFailed();
            UserAdministrationPort port = context.getBean(UserAdministrationPort.class);
            assertEquals(3, port.page(new Query(1L, 1, 10)).getTotal());
            assertTrue(port.grant(2L, 1L).changed());
            assertFalse(port.grant(2L, 1L).changed());
            assertTrue(port.revoke(2L, 1L).changed());
            assertEquals(ErrorCode.NOT_FOUND,
                    assertThrows(BizException.class, () -> port.grant(99L, 1L)).getErrorCode());
            assertEquals(ErrorCode.STATE_CONFLICT,
                    assertThrows(BizException.class, () -> port.revoke(3L, 1L)).getErrorCode());
            assertEquals(ErrorCode.SUPER_ADMIN_REQUIRED,
                    assertThrows(BizException.class, () -> port.grant(2L, 4L)).getErrorCode());
            assertEquals(ErrorCode.INVALID_ARGUMENT,
                    assertThrows(BizException.class, () -> port.page(new Query(1L, 0, 10))).getErrorCode());
            assertEquals(2, auditCount());
        });
    }

    @Test
    void invalidInternalCredentialsRemainDependencyFailuresInFeign() {
        runner("wrong").run(context -> {
            assertThat(context).hasNotFailed();
            UserAdministrationPort port = context.getBean(UserAdministrationPort.class);
            assertEquals(ErrorCode.DEPENDENCY_FAILURE,
                    assertThrows(DependencyException.class, () -> port.grant(2L, 1L)).getErrorCode());
            assertEquals(0, auditCount());
        });
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_role_change", Integer.class);
    }

    private ApplicationContextRunner runner(String token) {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                        FeignAutoConfiguration.class, JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class))
                .withUserConfiguration(Clients.class).withBean(Client.class, this::bridge)
                .withPropertyValues("my-youtube.user-service.url=http://user-service", "my-youtube.internal-token=" + token);
    }

    private Client bridge() {
        return (request, options) -> {
            MockHttpServletRequestBuilder builder = request(
                    org.springframework.http.HttpMethod.valueOf(request.httpMethod().name()), URI.create(request.url()));
            request.headers().forEach((name, values) -> builder.header(name, values.toArray()));
            if (request.body() != null) {
                builder.content(request.body());
            }
            try {
                MvcResult result = mvc.perform(builder).andReturn();
                Map<String, Collection<String>> headers = new HashMap<>();
                result.getResponse().getHeaderNames().forEach(name -> headers.put(name, result.getResponse().getHeaders(name)));
                return Response.builder().request(request).status(result.getResponse().getStatus())
                        .headers(headers).body(result.getResponse().getContentAsByteArray()).build();
            } catch (Exception error) {
                throw new IOException(error);
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = UserAdministrationPort.class, defaultConfiguration = InternalFeignConfiguration.class)
    static class Clients {}
}
