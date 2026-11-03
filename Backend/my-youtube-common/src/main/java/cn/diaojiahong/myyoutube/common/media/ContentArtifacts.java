package cn.diaojiahong.myyoutube.common.media;

import java.util.List;

public final class ContentArtifacts {
    public static final String ARTIFACT_KEY = "artifactKey";
    public static final String COVER_OBJECT_KEY = "coverObjectKey";
    public static final String VTT_KEY = "vttKey";
    public static final String SEGMENTS_KEY = "segmentsKey";
    public static final String DURATION_MS = "durationMs";

    private ContentArtifacts() {
    }

    public record Segment(long startMs, long endMs, String text) {
    }

    public record Transcript(List<Segment> segments) {
    }

    public record Frame(int imageIndex, long timeMs, String objectKey, String text, String derivedObjectKey) {
        public Frame(int imageIndex, long timeMs, String objectKey, String text) {
            this(imageIndex, timeMs, objectKey, text, null);
        }
    }

    public record Ocr(List<Frame> frames) {
    }

    public record Chapter(long startMs, String title) {
    }

    public record Summary(String summary, List<Chapter> chapters) {
    }
}
