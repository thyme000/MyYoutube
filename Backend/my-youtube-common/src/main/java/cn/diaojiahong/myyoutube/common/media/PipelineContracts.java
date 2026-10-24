package cn.diaojiahong.myyoutube.common.media;

import java.util.Map;

public final class PipelineContracts {
    private PipelineContracts() {}

    public record Input(Long pipelineId, Long contentId, Long userId, String title,
                        String description, String tags, String coverObjectKey, ContentMedia media, long submittedAt) {}

    public record Manifest(Long pipelineId, Long contentId, PipelineStatus status, Input input,
                           Map<String, String> results, long completedAt) {}

    public record ReviewSpec(boolean enabled, String model, String rulesVersion, String rules, double maxRisk,
                             double minConfidence, int chunkCharacters, boolean visionEnabled, int frameBatchSize) {
        public ReviewSpec {
            if (frameBatchSize < 1 || frameBatchSize > 8)
                throw new IllegalArgumentException("审核画面批次大小必须为 1~8");
        }
    }

    public record ReviewResult(Long pipelineId, Long contentId, Long taskId, int attempt,
                               String verdictJson, String error, boolean exhausted) {}

    public record TagRequest(String title, String description, String summary) {}
}
