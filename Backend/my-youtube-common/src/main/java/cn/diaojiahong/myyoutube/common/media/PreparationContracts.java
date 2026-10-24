package cn.diaojiahong.myyoutube.common.media;

import java.util.List;

public final class PreparationContracts {
    public static final String PARAMETERS_CHANGED_MESSAGE = "服务端媒体处理配置已更新，请点击重试以重新准备，无需修改或重新上传文件";

    private PreparationContracts() {}
    public record Request(Long preparationId, Long draftId, long mediaVersion, Long userId,
                          ContentMedia media, String coverObjectKey) {}
    public record Item(String role, int index, String originalObjectKey, String objectKey,
                       long timeMs, int width, int height) {}
    public record Manifest(Long preparationId, Long draftId, long mediaVersion, Long userId,
                           String parameterVersion, List<Item> items, long durationMs, boolean hasAudio, int videoFrameCount,
                           VideoFrameSampling.Plan videoSampling) {
        public Manifest { items = List.copyOf(items); }
    }
    public record View(Long preparationId, MediaTaskStatus status, int attempt, int retryCount,
                       String errorMessage, Manifest manifest, boolean parametersChanged) {}
}
