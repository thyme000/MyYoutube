package cn.diaojiahong.myyoutube.common.media;

import java.util.List;
import java.util.Objects;

public final class AudioAnalysisContracts {
    private AudioAnalysisContracts() {
    }

    public record Request(Long audioId, Long userId, String title, String objectKey, int durationMs,
                          MediaTaskType type, String transcriptKey) {
        public String prefix() {
            return "audio-artifacts/" + userId + "/" + audioId + "/";
        }

        public void validate() {
            if (audioId == null
                    || audioId <= 0
                    || userId == null
                    || userId <= 0
                    || title == null
                    || title.isBlank()
                    || title.length() > 100
                    || durationMs < 1 || durationMs > AudioPreparationContracts.MAX_DURATION_MS
                    || !Objects.equals(objectKey, "media-prepared/" + userId + "/" + audioId + "/audio-source")
                    || type != MediaTaskType.AUDIO_ASR && type != MediaTaskType.AUDIO_REVIEW) {
                throw new IllegalArgumentException("Invalid audio analysis input");
            }

            if (type == MediaTaskType.AUDIO_REVIEW) {
                artifact(transcriptKey);
            } else if (transcriptKey != null) {
                throw new IllegalArgumentException("Unexpected transcript input");
            }
        }

        public void artifact(String key) {
            MediaInputs.objectKey(key);
            if (!key.startsWith(prefix() + "asr/") || !key.endsWith(".json")) {
                throw new IllegalArgumentException("Audio artifact mismatch");
            }
        }
    }

    public record TranscriptResult(Long audioId, String objectKey, String transcriptKey, int segmentCount) {
        public void validate(Request request) {
            if (!Objects.equals(audioId, request.audioId())
                    || !Objects.equals(objectKey, request.objectKey())
                    || segmentCount < 0) {
                throw new IllegalArgumentException("Audio transcript identity mismatch");
            }

            request.artifact(transcriptKey);
        }
    }

    public record ScreenResult(Long audioId, String objectKey, String transcriptKey, int effectiveCharacters,
                               boolean modelSkipped, boolean localBlocked, List<ReviewVerdict> verdicts) {
        public void validate(Request request) {
            if (!Objects.equals(audioId, request.audioId()) || !Objects.equals(objectKey, request.objectKey())
                    || !Objects.equals(transcriptKey, request.transcriptKey())
                    || effectiveCharacters < 0
                    || verdicts == null
                    || modelSkipped != verdicts.isEmpty()) {
                throw new IllegalArgumentException("Audio screening identity mismatch");
            }
        }
    }

    public record View(
            Long audioId,
            MediaTaskType type,
            MediaTaskStatus status,
            int attempt,
            String resultJson,
            String failureCode) {
    }
}
