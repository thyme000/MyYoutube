package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.admin.controller.AdminAdministratorsController;
import cn.diaojiahong.myyoutube.admin.controller.AdminCheckInStatisticsController;
import cn.diaojiahong.myyoutube.admin.security.AdminAuthorization;
import cn.diaojiahong.myyoutube.admin.security.AdminWebConfiguration;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.security.AuthConstants;
import cn.diaojiahong.myyoutube.user.api.UserAdministrationPort;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Day;
import cn.diaojiahong.myyoutube.user.api.UserRolePort;
import feign.Client;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminFeignStartupTest {
    @Test
    void mvcAndRealFeignClientsInitializeWithoutCircularReferences() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class,
                        JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                        WebMvcAutoConfiguration.class))
                .withPropertyValues("my-youtube.user-service.url=http://127.0.0.1:1",
                        "my-youtube.internal-token=test-token")
                .withBean(Client.class, () -> (request, options) -> {
                    assertThat(request.url()).startsWith("http://127.0.0.1:1/internal/user/check-ins/daily?")
                            .contains("from=2024-02-01", "to=2024-02-02");
                    assertThat(request.headers().get(AuthConstants.INTERNAL_TOKEN_HEADER)).containsExactly("test-token");
                    return Response.builder().request(request).status(200)
                            .headers(Map.of("Content-Type", List.of("application/json")))
                            .body("[{\"statDate\":\"2024-02-01\",\"signedUsers\":3}]", StandardCharsets.UTF_8)
                            .build();
                })
                .withUserConfiguration(Clients.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("mvcResourceUrlProvider");
                    assertTrue(Proxy.isProxyClass(context.getBean(UserAdministrationPort.class).getClass()));
                    assertTrue(Proxy.isProxyClass(context.getBean(UserRolePort.class).getClass()));

                    UserCheckInStatisticsPort statistics = context.getBean(UserCheckInStatisticsPort.class);
                    LocalDate from = LocalDate.of(2024, 2, 1);
                    assertThat(statistics.daily(from, from.plusDays(1))).containsExactly(new Day(from, 3));

                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
                    mvc.perform(get("/admin/administrators")).andExpect(status().isUnauthorized());
                    mvc.perform(get("/admin/check-ins/yearly").param("year", "2024"))
                            .andExpect(status().isUnauthorized());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = {UserAdministrationPort.class, UserRolePort.class, UserCheckInStatisticsPort.class})
    @Import({AdminAdministratorsController.class, AdminCheckInStatisticsController.class, AdminWebConfiguration.class,
            AdminAuthorization.class, GlobalExceptionHandler.class})
    static class Clients {
    }
}
