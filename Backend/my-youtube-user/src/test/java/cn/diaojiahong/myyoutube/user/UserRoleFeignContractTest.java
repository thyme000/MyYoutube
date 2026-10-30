package cn.diaojiahong.myyoutube.user;

import cn.diaojiahong.myyoutube.common.security.InternalApiCredentials;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.feign.InternalFeignConfiguration;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.user.api.UserRolePort;
import cn.diaojiahong.myyoutube.user.api.enums.UserRole;
import cn.diaojiahong.myyoutube.user.controller.InternalUserRoleController;
import cn.diaojiahong.myyoutube.user.port.UserRolePortAdapter;
import feign.Client;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class UserRoleFeignContractTest {
    @Test
    void springFeignSendsInternalCredentialsAndDecodesRolesAndBusinessRejections() {
        UserRolePortAdapter roles = mock(UserRolePortAdapter.class);
        when(roles.getRole(7L)).thenReturn(UserRole.ADMIN);
        when(roles.getRole(8L)).thenThrow(new BizException(ErrorCode.NOT_FOUND));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalUserRoleController(
                roles,
                new InternalApiCredentials("contract-token")))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var calls = new AtomicInteger();

        runner().withBean(Client.class, () -> bridge(mvc, calls))
                .withPropertyValues("my-youtube.internal-token=contract-token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    UserRolePort port = context.getBean(UserRolePort.class);
                    assertEquals(UserRole.ADMIN, port.getRole(7L));
                    assertEquals(ErrorCode.NOT_FOUND,
                            assertThrows(BizException.class, () -> port.getRole(8L)).getErrorCode());
                    assertEquals(2, calls.get());
                    verify(roles).getRole(7L);
                    verify(roles).getRole(8L);
                    verifyNoMoreInteractions(roles);
                });
    }

    @Test
    void unconfiguredCredentialsAllowLocalRoleLookup() {
        UserRolePortAdapter roles = mock(UserRolePortAdapter.class);
        when(roles.getRole(7L)).thenReturn(UserRole.ADMIN);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalUserRoleController(
                roles,
                new InternalApiCredentials("")))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var calls = new AtomicInteger();

        runner().withBean(Client.class, () -> bridge(mvc, calls)).run(context -> {
            assertThat(context).hasNotFailed();
            assertEquals(UserRole.ADMIN, context.getBean(UserRolePort.class).getRole(7L));
            assertEquals(1, calls.get());
            verify(roles).getRole(7L);
        });
    }

    @Test
    void internalCredentialFailureIsADependencyFailureRatherThanUserLogout() {
        UserRolePortAdapter roles = mock(UserRolePortAdapter.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalUserRoleController(
                roles,
                new InternalApiCredentials("server-token")))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var calls = new AtomicInteger();

        runner().withBean(Client.class, () -> bridge(mvc, calls))
                .withPropertyValues("my-youtube.internal-token=wrong-token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DependencyException error = assertThrows(DependencyException.class,
                            () -> context.getBean(UserRolePort.class).getRole(7L));
                    assertEquals(ErrorCode.DEPENDENCY_FAILURE, error.getErrorCode());
                    assertEquals(1, calls.get());
                    verifyNoInteractions(roles);
                });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                        FeignAutoConfiguration.class, JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class))
                .withUserConfiguration(Clients.class)
                .withPropertyValues("my-youtube.user-service.url=http://user-service");
    }

    private Client bridge(MockMvc mvc, AtomicInteger calls) {
        return (request, options) -> {
            calls.incrementAndGet();
            MockHttpServletRequestBuilder builder = get(URI.create(request.url()).getPath());
            request.headers().forEach((name, values) -> builder.header(name, values.toArray()));
            try {
                MvcResult result = mvc.perform(builder).andReturn();
                Map<String, Collection<String>> headers = new HashMap<>();
                result.getResponse().getHeaderNames().forEach(name ->
                        headers.put(name, result.getResponse().getHeaders(name)));
                return Response.builder().request(request).status(result.getResponse().getStatus())
                        .headers(headers).body(result.getResponse().getContentAsByteArray()).build();
            } catch (Exception e) {
                throw new IOException(e);
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = UserRolePort.class, defaultConfiguration = InternalFeignConfiguration.class)
    static class Clients {
    }
}
