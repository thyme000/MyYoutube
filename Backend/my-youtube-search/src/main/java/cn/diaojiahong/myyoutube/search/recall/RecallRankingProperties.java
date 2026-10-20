package cn.diaojiahong.myyoutube.search.recall;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.search.ranking")
public class RecallRankingProperties {
    private Fields fields = new Fields();
    private Weights keyword = new Weights(0.20, 0.02, 0.04, 1.50);
    private Weights vector = new Weights(0.08, 0.01, 0.02, 1.20);

    @Data
    public static class Fields {
        private double title = 5;
        private double tagNames = 4;
        private double authorName = 2;
        private double description = 3;

        public List<String> boostedFields() {
            return List.of("title^" + title, "tagNames^" + tagNames,
                    "authorName^" + authorName, "description^" + description);
        }
    }

    @Data
    @AllArgsConstructor
    public static class Weights {
        private double follow;
        private double play;
        private double like;
        private double maxMultiplier;
    }

    @PostConstruct
    void validate() {
        if (fields == null || keyword == null || vector == null
                || !positive(fields.title) || !positive(fields.tagNames)
                || !positive(fields.authorName) || !positive(fields.description)) {
            throw new IllegalStateException("搜索字段权重必须为有限正数，打分配置不能为空");
        }
        for (Weights weights : List.of(keyword, vector)) {
            if (!nonNegative(weights.follow) || !nonNegative(weights.play) || !nonNegative(weights.like)
                    || !Double.isFinite(weights.maxMultiplier) || weights.maxMultiplier < 1) {
                throw new IllegalStateException("搜索加成必须为有限非负数，最大倍率必须为有限数且不小于 1");
            }
        }
    }

    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
    private static boolean nonNegative(double value) { return Double.isFinite(value) && value >= 0; }
}
