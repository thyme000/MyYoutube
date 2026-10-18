package cn.diaojiahong.myyoutube.common.media;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.Map;
import java.util.Set;

@Component
public class FrameProcessingSettings {
    private static final Set<String> KEYS = Set.of("interval-seconds", "max-frames", "cover-max-candidates", "ocr-batch-size", "review-batch-size", "min-success-percent");
    private volatile Snapshot current;

    public FrameProcessingSettings() { current = new Snapshot(5, 10, 8, 2, 4, 80); }

    @Autowired
    public FrameProcessingSettings(Environment environment) {
        String prefix = "my-youtube.frame-processing.";
        current = new Snapshot(environment.getProperty(prefix + "interval-seconds", Integer.class, 5),
                environment.getProperty(prefix + "max-frames", Integer.class, 10),
                environment.getProperty(prefix + "cover-max-candidates", Integer.class, 8),
                environment.getProperty(prefix + "ocr-batch-size", Integer.class, 2),
                environment.getProperty(prefix + "review-batch-size", Integer.class, 4),
                environment.getProperty(prefix + "min-success-percent", Integer.class, 80));
    }

    public Snapshot snapshot() { return current; }

    public synchronized void replace(String yaml) {
        if (yaml == null || yaml.isBlank() || yaml.length() > 8192) throw new IllegalArgumentException("帧处理配置为空或过长");
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(8192);
        Object value = new Yaml(new SafeConstructor(options)).load(yaml);
        if (!(value instanceof Map<?, ?> values) || !values.keySet().equals(KEYS))
            throw new IllegalArgumentException("帧处理配置必须包含全部六个已知配置项");
        current = new Snapshot(integer(values, "interval-seconds"), integer(values, "max-frames"),
                integer(values, "cover-max-candidates"), integer(values, "ocr-batch-size"), integer(values, "review-batch-size"),
                integer(values, "min-success-percent"));
    }

    private static int integer(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Integer)) throw new IllegalArgumentException("帧处理配置必须为整数: " + key);
        return (Integer) value;
    }

    public record Snapshot(int intervalSeconds, int maxFrames, int coverMaxCandidates, int ocrBatchSize, int reviewBatchSize,
                           int minSuccessPercent) {
        public Snapshot {
            if (intervalSeconds < 1 || intervalSeconds > 3600 || maxFrames < 1 || maxFrames > VideoFrameSampling.MAX_FRAMES
                    || coverMaxCandidates < 1 || coverMaxCandidates > VideoFrameSampling.MAX_FRAMES
                    || ocrBatchSize < 1 || ocrBatchSize > 50 || reviewBatchSize < 1 || reviewBatchSize > 8
                    || minSuccessPercent < 1 || minSuccessPercent > 100)
                throw new IllegalArgumentException("帧处理配置超出允许范围");
        }
        public int count(long durationMs, long availableFrames) {
            return VideoFrameSampling.count(durationMs, availableFrames, intervalSeconds * 1000L, maxFrames);
        }
        public VideoFrameSampling.Plan plan(long durationMs, long availableFrames) {
            return VideoFrameSampling.plan(count(durationMs, availableFrames), minSuccessPercent);
        }
    }
}
