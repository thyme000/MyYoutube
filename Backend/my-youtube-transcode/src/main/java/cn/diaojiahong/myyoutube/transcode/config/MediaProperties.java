package cn.diaojiahong.myyoutube.transcode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.media")
public class MediaProperties {

    /** 对象存储 webhook 的共享密钥, 置空即不校验; 配置在 webhook 目标 URL 的 ?token= 上 */
    private String webhookToken = "";

    /** 打标视觉模型; 留空回落 llm-svc 默认模型(需具备视觉能力) */
    private String tagVisionModel = "";
}
