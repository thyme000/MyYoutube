package cn.diaojiahong.myyoutube.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    private static final String AUTH_HEADER = "Authorization";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("MyYoutube API")
                        .description("MyYoutube 后端接口文档")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("thyme")
                        )
                )
                .addSecurityItem(new SecurityRequirement().addList(AUTH_HEADER))
                .components(new Components()
                        .addSecuritySchemes(AUTH_HEADER,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name(AUTH_HEADER)
                                        .description("登录返回的 Token，整段粘贴")));
    }
}
