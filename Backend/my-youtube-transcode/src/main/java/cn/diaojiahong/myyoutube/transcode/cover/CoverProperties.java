package cn.diaojiahong.myyoutube.transcode.cover;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 封面生成配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.cover")
public class CoverProperties {

    /** 封面选帧视觉模型; 留空回落 llm-svc 默认模型 */
    private String visionModel;

    public String effectiveVisionModel() {
        return visionModel == null || visionModel.isBlank() ? null : visionModel;
    }
}
