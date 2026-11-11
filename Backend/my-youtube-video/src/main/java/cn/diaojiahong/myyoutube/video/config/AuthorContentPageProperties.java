package cn.diaojiahong.myyoutube.video.config;

import cn.diaojiahong.myyoutube.common.page.CursorPageQuery;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
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
    private int pageSize = CursorPageQuery.PAGE_SIZE;

    @PostConstruct
    public void validate() {
        if (scanLimit < 1 || scanBudgetMs < 1)
            throw new IllegalStateException("作者作品分页补取配置无效");
        if (pageSize < 1 || pageSize > PageQuery.MAX_PAGE_SIZE)
            throw new IllegalStateException("作者作品每页条数必须为 1~" + PageQuery.MAX_PAGE_SIZE);
    }
}
