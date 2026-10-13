package cn.diaojiahong.myyoutube.video.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.content-pipeline")
public class ContentPipelineProperties {

    private boolean autoReview;
    private boolean reviewVisionEnabled;

    private double maxRisk = 0.2;
    private double minConfidence = 0.9;
    private int evidenceChunkCharacters = 12000;
    private int reviewFrameBatchSize = 4;

    private String reviewModel = "";

    @jakarta.annotation.PostConstruct
    public void validate() {
        if (!Double.isFinite(maxRisk) || maxRisk < 0 || maxRisk > 1 || !Double.isFinite(minConfidence)
                || minConfidence < 0 || minConfidence > 1 || evidenceChunkCharacters < 1024 || evidenceChunkCharacters > 30000)
            throw new IllegalStateException("内容审核阈值或分批预算非法");

        if (reviewFrameBatchSize < 1 || reviewFrameBatchSize > 8)
            throw new IllegalStateException("审核画面批次大小必须为 1~8");

        if (autoReview && (reviewModel == null || reviewModel.isBlank()))
            throw new IllegalStateException("自动审核需要配置 CONTENT_REVIEW_MODEL，开启画面审核时须支持图片输入");
    }
}
