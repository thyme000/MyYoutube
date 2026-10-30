package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.admin.controller.*;
import cn.diaojiahong.myyoutube.admin.security.AdminAuthorization;
import cn.diaojiahong.myyoutube.admin.security.AdminWebConfiguration;
import cn.diaojiahong.myyoutube.admin.service.impl.AdminServiceImpl;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.feign.FeignExceptionHandler;
import cn.diaojiahong.myyoutube.common.filter.UserIdHeaderFilter;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.search.api.ContentVectorDeadLetterPort;
import cn.diaojiahong.myyoutube.search.api.HotSearchHistoryPort;
import cn.diaojiahong.myyoutube.security.AuthConstants;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.UserAdministrationPort;
import cn.diaojiahong.myyoutube.user.api.UserRolePort;
import cn.diaojiahong.myyoutube.user.api.enums.UserRole;
import cn.diaojiahong.myyoutube.video.api.AudioAdminPort;
import cn.diaojiahong.myyoutube.video.api.CommentAdminPort;
import cn.diaojiahong.myyoutube.video.api.ContentAdminPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminWebAuthorizationTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private UserRolePort roles;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebTestConfiguration.class);
        context.refresh();
        roles = context.getBean(UserRolePort.class);
        when(roles.getRole(anyLong())).thenReturn(UserRole.USER);

        var filter = new UserIdHeaderFilter(new ObjectMapper());
        ReflectionTestUtils.setField(filter, "internalToken", "web-test-token");
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
    }

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
        context.close();
    }

    @ParameterizedTest
    @MethodSource("routes")
    void everyManagementRouteRejectsAnonymousAndOrdinaryUsers(Route route) throws Exception {
        mvc.perform(route.request()).andExpect(status().isUnauthorized());
        mvc.perform(authenticated(route.request(), "1"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40301));

        verify(roles).getRole(1L);
        verifyNoMoreInteractions(roles);
        verifyNoInteractions(context.getBean(ContentAdminPort.class), context.getBean(CommentAdminPort.class),
                context.getBean(AudioAdminPort.class), context.getBean(MediaRetryPort.class),
                context.getBean(HotSearchHistoryPort.class), context.getBean(ContentVectorDeadLetterPort.class));
        assertNull(UserContext.getUserId());
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"ADMIN", "SUPER_ADMIN"})
    void bothAdminRolesCanUseEveryRouteWithOneRoleLookupPerRequest(UserRole role) throws Exception {
        when(roles.getRole(42L)).thenReturn(role);
        ContentAdminPort content = context.getBean(ContentAdminPort.class);
        CommentAdminPort comments = context.getBean(CommentAdminPort.class);
        when(content.markStatus(any())).thenReturn(true);
        when(content.pageContents(any())).thenReturn(PageResult.of(0, 0, List.of()));
        when(comments.pageBlockedComments(any())).thenReturn(PageResult.of(0, 0, List.of()));
        when(comments.pageReports(any())).thenReturn(PageResult.of(0, 0, List.of()));
        when(context.getBean(MediaRetryPort.class).create(any(), eq(42L)))
                .thenReturn(new MediaRetryPort.Admission(true, "RETRY_BATCH_CREATED", "已登记", null));
        when(context.getBean(ContentVectorDeadLetterPort.class).replay(any(), eq(42L)))
                .thenReturn(new ContentVectorDeadLetterPort.ReplayResult(
                        ContentVectorDeadLetterPort.ReplayStatus.ACCEPTED, 7L, "已登记"));

        List<Route> routes = routes().toList();
        for (Route route : routes) {
            mvc.perform(authenticated(route.request(), "42"))
                    .andExpect(status().is(route.accepted() ? 202 : 200));
        }

        verify(roles, times(routes.size())).getRole(42L);
        verifyNoMoreInteractions(roles);
    }

    @Test
    void newManagementRoutesAreProtectedWithoutControllerChecks() throws Exception {
        mvc.perform(authenticated(get("/admin/probe"), "1")).andExpect(status().isForbidden());
        when(roles.getRole(42L)).thenReturn(UserRole.ADMIN);
        mvc.perform(authenticated(get("/admin/probe"), "42")).andExpect(status().isOk());
    }

    @Test
    void roleChangesAreObservedOnTheNextRequestForTheSameIdentity() throws Exception {
        when(roles.getRole(42L)).thenReturn(UserRole.USER, UserRole.ADMIN, UserRole.USER);
        for (int status : List.of(403, 200, 403)) {
            mvc.perform(authenticated(get("/admin/probe"), "42")
                            .header("Authorization", "Bearer unchanged-token"))
                    .andExpect(status().is(status));
        }
        verify(roles, times(3)).getRole(42L);
    }

    @Test
    void writeOperationsUseTheAuthenticatedActorInsteadOfBodyOrQueryParameters() throws Exception {
        when(roles.getRole(42L)).thenReturn(UserRole.ADMIN);
        when(context.getBean(ContentAdminPort.class).markStatus(any())).thenReturn(true);
        when(context.getBean(MediaRetryPort.class).create(any(), eq(42L)))
                .thenReturn(new MediaRetryPort.Admission(true, "RETRY_BATCH_CREATED", "已登记", null));
        when(context.getBean(ContentVectorDeadLetterPort.class).replay(any(), eq(42L)))
                .thenReturn(new ContentVectorDeadLetterPort.ReplayResult(
                        ContentVectorDeadLetterPort.ReplayStatus.ACCEPTED, 7L, "已登记"));

        mvc.perform(authenticated(post("/admin/audios/7/decision"), "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"BAN\",\"reason\":\"审核原因\",\"actorId\":99}"))
                .andExpect(status().isOk());
        mvc.perform(authenticated(post("/admin/review/pass/7"), "42")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"审核原因\",\"actorId\":99}"))
                .andExpect(status().isOk());
        mvc.perform(authenticated(post("/admin/media-retry-batches").param("actorId", "99"), "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"one\",\"taskIds\":[7],\"actorId\":99}"))
                .andExpect(status().isAccepted());
        mvc.perform(authenticated(
                post("/admin/dead-letters/content-vectors/messages/replay").param("actorId", "99"),
                "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brokerName\":\"broker\",\"queueId\":0,\"offset\":5,\"actorId\":99}"))
                .andExpect(status().isAccepted());

        verify(context.getBean(AudioAdminPort.class)).decide(7L, new AudioAdminPort.Decision("BAN", "审核原因", 42L));
        verify(context.getBean(ContentAdminPort.class))
                .markStatus(argThat(decision -> decision.getActorId().equals(42L)));
        verify(context.getBean(MediaRetryPort.class)).create(any(), eq(42L));
        verify(context.getBean(ContentVectorDeadLetterPort.class)).replay(any(), eq(42L));
        verify(roles, times(4)).getRole(42L);
    }

    @Test
    void missingAccountsAndRoleServiceFailuresCannotReachBusinessOperations() throws Exception {
        when(roles.getRole(42L)).thenThrow(new BizException(ErrorCode.NOT_FOUND));
        mvc.perform(authenticated(get("/admin/probe"), "42")).andExpect(status().isForbidden());
        when(roles.getRole(43L)).thenThrow(new DependencyException(ErrorCode.DEPENDENCY_TIMEOUT, "timeout", null));
        mvc.perform(authenticated(get("/admin/probe"), "43")).andExpect(status().isGatewayTimeout());
        when(roles.getRole(44L)).thenReturn(null);
        mvc.perform(authenticated(get("/admin/probe"), "44")).andExpect(status().isBadGateway());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad-id", "0", "-1"})
    void invalidIdentitiesAreUnauthorized(String identity) throws Exception {
        mvc.perform(authenticated(get("/admin/probe"), identity)).andExpect(status().isUnauthorized());
        verifyNoInteractions(roles);
    }

    @Test
    void forgedRoleAndUntrustedIdentityHeadersCannotGrantAccess() throws Exception {
        mvc.perform(authenticated(get("/admin/probe"), "1").header("X-User-Role", "SUPER_ADMIN"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/probe").header(AuthConstants.USER_ID_HEADER, "42")
                        .header(AuthConstants.INTERNAL_TOKEN_HEADER, "wrong"))
                .andExpect(status().isUnauthorized());
        verify(roles).getRole(1L);
        verifyNoMoreInteractions(roles);
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void administratorManagementRequiresTheSuperAdministratorRole(UserRole role) throws Exception {
        when(roles.getRole(42L)).thenReturn(role);
        UserAdministrationPort administration = context.getBean(UserAdministrationPort.class);
        when(administration.page(any())).thenReturn(PageResult.of(0, 0, List.of()));
        when(administration.grant(7L, 42L)).thenReturn(new UserAdministrationPort.Change(7L, UserRole.ADMIN, true));
        when(administration.revoke(7L, 42L)).thenReturn(new UserAdministrationPort.Change(7L, UserRole.USER, true));
        int expected = role == UserRole.SUPER_ADMIN ? 200 : 403;

        mvc.perform(authenticated(get("/admin/administrators"), "42"))
                .andExpect(status().is(expected));
        mvc.perform(authenticated(put("/admin/administrators/7"), "42"))
                .andExpect(status().is(expected));
        mvc.perform(authenticated(delete("/admin/administrators/7"), "42"))
                .andExpect(status().is(expected));

        verify(roles, times(3)).getRole(42L);
        if (role != UserRole.SUPER_ADMIN) {
            verifyNoInteractions(administration);
        }
    }

    @Test
    void anonymousRequestsCannotManageAdministrators() throws Exception {
        mvc.perform(get("/admin/administrators")).andExpect(status().isUnauthorized());
        mvc.perform(put("/admin/administrators/7")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/admin/administrators/7")).andExpect(status().isUnauthorized());
        verifyNoInteractions(roles, context.getBean(UserAdministrationPort.class));
    }

    @Test
    void administratorCommandsIgnoreCallerSuppliedActorAndRole() throws Exception {
        when(roles.getRole(42L)).thenReturn(UserRole.SUPER_ADMIN);
        UserAdministrationPort administration = context.getBean(UserAdministrationPort.class);
        when(administration.page(any())).thenReturn(PageResult.of(1, 1,
                List.of(new UserAdministrationPort.Administrator(7L, "moderator", "审核员", UserRole.ADMIN))));
        when(administration.grant(7L, 42L)).thenReturn(new UserAdministrationPort.Change(7L, UserRole.ADMIN, true));
        when(administration.revoke(7L, 42L)).thenReturn(new UserAdministrationPort.Change(7L, UserRole.USER, false));

        mvc.perform(authenticated(get("/admin/administrators").param("pageNo", "2").param("pageSize", "5")
                        .param("actorId", "99"), "42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.list[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.data.list[0].password").doesNotExist());
        mvc.perform(authenticated(put("/admin/administrators/7").param("actorId", "99"), "42")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actorId\":99,\"role\":\"SUPER_ADMIN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.changed").value(true));
        mvc.perform(authenticated(delete("/admin/administrators/7").param("actorId", "99"), "42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(false));

        verify(administration).page(new UserAdministrationPort.Query(42L, 2, 5));
        verify(administration).grant(7L, 42L);
        verify(administration).revoke(7L, 42L);
        verify(roles, times(3)).getRole(42L);
    }

    @Test
    void superAdministratorPrivilegesAreNotRetainedAcrossRequests() throws Exception {
        when(roles.getRole(42L)).thenReturn(UserRole.SUPER_ADMIN, UserRole.ADMIN);
        UserAdministrationPort administration = context.getBean(UserAdministrationPort.class);
        when(administration.grant(7L, 42L)).thenReturn(new UserAdministrationPort.Change(7L, UserRole.ADMIN, true));
        mvc.perform(authenticated(put("/admin/administrators/7"), "42")).andExpect(status().isOk());
        mvc.perform(authenticated(delete("/admin/administrators/7"), "42"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40302));
        verify(administration).grant(7L, 42L);
        verifyNoMoreInteractions(administration);
        verify(roles, times(2)).getRole(42L);
    }

    @Test
    void invalidAdministratorPaginationIsRejectedBeforeCallingUserService() throws Exception {
        when(roles.getRole(42L)).thenReturn(UserRole.SUPER_ADMIN);
        mvc.perform(authenticated(get("/admin/administrators").param("pageNo", "0"), "42"))
                .andExpect(status().isBadRequest());
        mvc.perform(authenticated(get("/admin/administrators").param("pageSize", "101"), "42"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(context.getBean(UserAdministrationPort.class));
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request, String userId) {
        return request.header(AuthConstants.USER_ID_HEADER, userId)
                .header(AuthConstants.INTERNAL_TOKEN_HEADER, "web-test-token");
    }

    private record Route(String path, String body, boolean accepted) {
        MockHttpServletRequestBuilder request() {
            return body == null ? get(path) : post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        }
    }

    private static Stream<Route> routes() {
        return Stream.of(
                new Route("/admin/content/7/media", null, false),
                new Route("/admin/content/7", null, false),
                new Route("/admin/content/waiting", null, false),
                new Route("/admin/review/pass/7", "{\"reason\":\"审核原因\"}", false),
                new Route("/admin/review/ban/7", "{\"reason\":\"审核原因\"}", false),
                new Route("/admin/list", null, false),
                new Route("/admin/comment/blocked/7", "{}", false),
                new Route("/admin/comment/list", null, false),
                new Route("/admin/comment/restore/7", "{}", false),
                new Route("/admin/comment/report/ignore/7", "{}", false),
                new Route("/admin/comment/report", null, false),
                new Route("/admin/audios/waiting", null, false),
                new Route("/admin/audios/7", null, false),
                new Route("/admin/audios/7/decision", "{\"decision\":\"PASS\",\"reason\":\"审核原因\"}", false),
                new Route("/admin/media-tasks", null, false),
                new Route("/admin/media-retry-batches", "{\"requestId\":\"one\",\"taskIds\":[7]}", true),
                new Route("/admin/media-retry-batches", null, false),
                new Route("/admin/media-retry-batches/current", null, false),
                new Route("/admin/media-retry-batches/7", null, false),
                new Route("/admin/media-retry-batches/7/tasks", null, false),
                new Route("/admin/media-retry-batches/7/items", null, false),
                new Route("/admin/hot-search/dates", null, false),
                new Route("/admin/hot-search/groups?date=2026-10-29", null, false),
                new Route("/admin/hot-search/keywords?date=2026-10-29&groupId=g", null, false),
                new Route("/admin/hot-search/users?date=2026-10-29&groupId=g&keywordId=7", null, false),
                new Route("/admin/hot-search/events?date=2026-10-29&groupId=g&keywordId=7", null, false),
                new Route("/admin/dead-letters/content-vectors/messages", null, false),
                new Route(
                        "/admin/dead-letters/content-vectors/messages/replay",
                        "{\"brokerName\":\"broker\",\"queueId\":0,\"offset\":5}",
                        true));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/admin/probe")
        public String probe() {
            return "ok";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({AdminWebConfiguration.class, AdminAuthorization.class, AdminServiceImpl.class,
            AdminController.class, AdminAudioController.class, AdminMediaRetryController.class,
            AdminHotSearchHistoryController.class, AdminContentVectorDeadLetterController.class,
            AdminAdministratorsController.class,
            GlobalExceptionHandler.class, FeignExceptionHandler.class, ProbeController.class})
    static class WebTestConfiguration {
        @Bean
        UserRolePort roles() {
            return mock(UserRolePort.class);
        }

        @Bean
        UserAdministrationPort administration() {
            return mock(UserAdministrationPort.class);
        }

        @Bean
        UserPort users() {
            return mock(UserPort.class);
        }

        @Bean
        ContentAdminPort content() {
            return mock(ContentAdminPort.class);
        }

        @Bean
        CommentAdminPort comments() {
            return mock(CommentAdminPort.class);
        }

        @Bean
        AudioAdminPort audios() {
            return mock(AudioAdminPort.class);
        }

        @Bean
        MediaRetryPort retries() {
            return mock(MediaRetryPort.class);
        }

        @Bean
        HotSearchHistoryPort hotSearch() {
            return mock(HotSearchHistoryPort.class);
        }

        @Bean
        ContentVectorDeadLetterPort deadLetters() {
            return mock(ContentVectorDeadLetterPort.class);
        }

        @Bean
        S3StorageUtil storage() {
            return mock(S3StorageUtil.class);
        }
    }
}
