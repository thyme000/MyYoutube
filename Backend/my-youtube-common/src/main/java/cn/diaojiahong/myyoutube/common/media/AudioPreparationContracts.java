package cn.diaojiahong.myyoutube.common.media;

public final class AudioPreparationContracts {
    public static final long MAX_SOURCE_BYTES = 128L * 1024 * 1024;
    public static final long MAX_DURATION_MS = 600_000;

    private AudioPreparationContracts() {}

    public record Request(Long audioId, Long userId, String objectKey, String coverObjectKey) {
        public void validate() {
            if (audioId == null || audioId <= 0 || userId == null || userId <= 0)
                throw new IllegalArgumentException("音频身份无效");
            upload(objectKey, "audio", userId);
            if (coverObjectKey != null) upload(coverObjectKey, "audio-cover", userId);
        }
        public String prefix() { return "media-prepared/" + userId + "/" + audioId + "/audio-"; }
    }

    public static void upload(String key, String directory, Long userId) {
        MediaInputs.objectKey(key);
        if (key.length() > 512 || !key.startsWith("uploads/" + directory + "/" + userId + "/") || key.contains("//"))
            throw new IllegalArgumentException("无权使用该上传文件");
    }

    public record Result(Long audioId, Long userId, String objectKey, String coverObjectKey,
                         long durationMs, long bytes, String mime, int coverWidth, int coverHeight) {
        public void validate(Request request) {
            request.validate();
            if (!request.audioId().equals(audioId) || !request.userId().equals(userId)
                    || !(request.prefix() + "source").equals(objectKey)
                    || durationMs <= 0 || durationMs > MAX_DURATION_MS || bytes <= 0 || bytes > MAX_SOURCE_BYTES
                    || mime == null || !mime.startsWith("audio/"))
                throw new IllegalArgumentException("音频准备结果不匹配");
            if (request.coverObjectKey() == null ? coverObjectKey != null || coverWidth != 0 || coverHeight != 0
                    : !(request.prefix() + "cover.jpg").equals(coverObjectKey) || coverWidth <= 0 || coverHeight <= 0)
                throw new IllegalArgumentException("音频封面准备结果不匹配");
        }
    }

    public record View(Long audioId, MediaTaskStatus status, int attempt, int retryCount,
                       Result result, String errorMessage, String failureCode) {}
}
