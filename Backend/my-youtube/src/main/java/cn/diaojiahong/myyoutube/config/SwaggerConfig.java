package cn.diaojiahong.myyoutube.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

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
                );
    }
}
