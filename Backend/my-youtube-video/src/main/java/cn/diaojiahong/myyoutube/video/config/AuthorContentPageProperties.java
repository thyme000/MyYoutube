package cn.diaojiahong.myyoutube.video.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.author-content-page")
public class AuthorContentPageProperties {
    private int scanLimit = 500;
    private int scanBudgetMs = 200;

    @PostConstruct
    public void validate() {
        if (scanLimit < 1 || scanBudgetMs < 1)
            throw new IllegalStateException("作者作品分页补取配置无效");
    }
}
