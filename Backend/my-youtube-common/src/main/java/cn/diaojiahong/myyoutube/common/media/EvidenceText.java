package cn.diaojiahong.myyoutube.common.media;

import java.util.ArrayList;
import java.util.List;

public final class EvidenceText {
    private EvidenceText() {
    }

    public static String full(
            String title,
            String description,
            ContentArtifacts.Transcript transcript,
            ContentArtifacts.Ocr ocr) {
        validate(transcript, ocr);
        StringBuilder text = new StringBuilder("标题：").append(title == null ? "" : title)
                .append("\n简介：").append(description == null ? "" : description).append("\n");
        for (ContentArtifacts.Segment segment : transcript.segments()) {
            text.append("ASR[").append(segment.startMs()).append(",")
                    .append(segment.endMs()).append("] ").append(segment.text()).append("\n");
        }
        for (ContentArtifacts.Frame frame : ocr.frames()) {
            text.append("OCR image#").append(frame.imageIndex()).append("[").append(frame.timeMs()).append("] ")
                    .append(frame.text()).append("\n");
        }
        return text.toString();
    }

    public static boolean empty(
            String title,
            String description,
            ContentArtifacts.Transcript transcript,
            ContentArtifacts.Ocr ocr) {
        validate(transcript, ocr);
        return (title == null || title.isBlank()) && (description == null || description.isBlank())
                && transcript.segments().stream().allMatch(s -> s.text().isBlank())
                && ocr.frames().stream().allMatch(f -> f.text().isBlank());
    }

    private static void validate(ContentArtifacts.Transcript transcript, ContentArtifacts.Ocr ocr) {
        if (transcript == null || transcript.segments() == null || ocr == null || ocr.frames() == null
                || transcript.segments()
                .stream()
                .anyMatch(s -> s == null || s.text() == null || s.startMs() < 0 || s.endMs() < s.startMs())
                || ocr.frames().stream().anyMatch(f -> f == null || f.text() == null || f.imageIndex() < 0 || f.timeMs() < 0 || f.objectKey() == null || f.objectKey().isBlank())) {
            throw new IllegalArgumentException("内容证据不完整");
        }
    }

    public static List<String> chunks(String text, int limit) {
        if (limit < 512) {
            throw new IllegalArgumentException("证据分批预算至少 512 字符");
        }

        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); ) {
            int end = Math.min(text.length(), start + limit);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks.isEmpty() ? List.of("") : chunks;
    }
}
