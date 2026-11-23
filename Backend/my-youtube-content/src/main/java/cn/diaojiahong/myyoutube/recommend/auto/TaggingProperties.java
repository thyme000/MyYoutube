package cn.diaojiahong.myyoutube.recommend.auto;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.autotag")
public class TaggingProperties {
    private boolean visionEnabled;
}
