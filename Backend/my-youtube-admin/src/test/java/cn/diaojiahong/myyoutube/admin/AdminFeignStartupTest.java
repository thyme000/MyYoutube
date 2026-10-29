package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.admin.controller.AdminAdministratorsController;
import cn.diaojiahong.myyoutube.admin.security.AdminAuthorization;
import cn.diaojiahong.myyoutube.admin.security.AdminWebConfiguration;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.user.api.UserAdministrationPort;
import cn.diaojiahong.myyoutube.user.api.UserRolePort;
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
                .withPropertyValues("my-youtube.user-service.url=http://127.0.0.1:1")
                .withUserConfiguration(Clients.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("mvcResourceUrlProvider");
                    assertTrue(Proxy.isProxyClass(context.getBean(UserAdministrationPort.class).getClass()));
                    assertTrue(Proxy.isProxyClass(context.getBean(UserRolePort.class).getClass()));

                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
                    mvc.perform(get("/admin/administrators")).andExpect(status().isUnauthorized());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = {UserAdministrationPort.class, UserRolePort.class})
    @Import({AdminAdministratorsController.class, AdminWebConfiguration.class,
            AdminAuthorization.class, GlobalExceptionHandler.class})
    static class Clients {}
}
