package cn.diaojiahong.myyoutube.common.media;

public final class AudioTranscript {
    private AudioTranscript() {
    }

    public static void validate(ContentArtifacts.Transcript value, long durationMs) {
        if (value == null || value.segments() == null || value.segments().size() > 10000) {
            throw new IllegalArgumentException("Invalid audio transcript");
        }

        long characters = 0;
        for (ContentArtifacts.Segment segment : value.segments()) {
            if (segment == null
                    || segment.text() == null
                    || segment.startMs() < 0
                    || segment.endMs() <= segment.startMs()
                    || segment.endMs() > durationMs + 1000) {
                throw new IllegalArgumentException("Invalid audio transcript segment");
            }
            characters += segment.text().length();
        }

        if (characters > 100000) {
            throw new IllegalArgumentException("Audio transcript exceeds evidence limit");
        }
    }
}
