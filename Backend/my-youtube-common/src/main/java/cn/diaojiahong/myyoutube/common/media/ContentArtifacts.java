package cn.diaojiahong.myyoutube.common.media;

import java.util.List;

public final class ContentArtifacts {
    private ContentArtifacts() {}
    public record Segment(long startMs, long endMs, String text) {}
    public record Transcript(List<Segment> segments) {}
    public record Frame(int imageIndex, long timeMs, String objectKey, String text, String derivedObjectKey) {
        public Frame(int imageIndex, long timeMs, String objectKey, String text) { this(imageIndex, timeMs, objectKey, text, null); }
    }
    public record Ocr(List<Frame> frames) {}
    public record Chapter(long startMs, String title) {}
    public record Summary(String summary, List<Chapter> chapters) {}
}
