package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import cn.diaojiahong.myyoutube.transcode.preparation.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparedVideoToleranceTest {
    @TempDir Path work;

    final MediaPreparationProperties properties = new MediaPreparationProperties();

    final PreparedVideoReader reader = new PreparedVideoReader(
            properties,
            new PreparedImageWriter(properties),
            new FrameProcessingSettings());

    final PreparedVideoReader.Probe probe = new PreparedVideoReader.Probe(50000, 80, 60, 0, false, "High", 42, 30);

    final Map<Integer, Integer> calls = new HashMap<>();

    void decode(MockedStatic<FFmpegUtil> ffmpeg, Set<Integer> missing, int fatalIndex, int fatalCode) {
        ffmpeg.when(() -> FFmpegUtil.runBoundedCommand(anyList(), any())).thenAnswer(call -> {
            List<String> command = call.getArgument(0);
            Path raw = Path.of(command.get(command.size() - 1));
            int index = Integer.parseInt(raw.getFileName().toString().replace("frame-", "").replace(".jpg", ""));
            calls.merge(index, 1, Integer::sum);
            if (index == fatalIndex) return new FFmpegUtil.CommandOutput(fatalCode,
                    fatalCode < 0 ? "process timeout" : "Error opening input: connection refused");
            if (missing.contains(index)) return new FFmpegUtil.CommandOutput(1, "Error while decoding stream");
            var image = new BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB);
            try {
                ImageIO.write(image, "jpg", raw.toFile());
            } finally {
                image.flush();
            }
            return new FFmpegUtil.CommandOutput(0, "pts_time:" + index * 5);
        });
    }

    @Test
    void isolatedMissingSlotsAreRetriedOnceAndEightActualFramesPass() throws Exception {
        try (MockedStatic<FFmpegUtil> ffmpeg = mockStatic(FFmpegUtil.class, CALLS_REAL_METHODS)) {
            decode(ffmpeg, Set.of(2, 7), -1, 0);
            var indices = new ArrayList<Integer>();
            reader.frames("source", probe, work, frame -> indices.add(frame.index()));
            assertEquals(List.of(0, 1, 3, 4, 5, 6, 8, 9), indices);
            assertEquals(2, calls.get(2));
            assertEquals(2, calls.get(7));
            assertEquals(12, calls.values().stream().mapToInt(Integer::intValue).sum());
            try (Stream<Path> files = Files.list(work)) {
                assertEquals(0, files.count());
            }
        }
    }

    @Test
    void tooFewFramesOrConsecutiveGapsFailAfterTryingAllSlots() {
        for (Set<Integer> missing : List.of(Set.of(2, 5, 7), Set.of(2, 3), Set.of(0, 1), Set.of(8, 9))) {
            calls.clear();
            try (MockedStatic<FFmpegUtil> ffmpeg = mockStatic(FFmpegUtil.class, CALLS_REAL_METHODS)) {
                decode(ffmpeg, missing, -1, 0);
                IllegalArgumentException error = assertThrows(
                        IllegalArgumentException.class,
                        () -> reader.frames("source", probe, work, frame -> {}));
                assertTrue(error.getMessage().contains("missing="));
                assertEquals(10, calls.size());
            }
        }
    }

    @Test
    void timeoutAndSourceLossCannotBeAcceptedAfterEnoughFramesExist() {
        for (int code : List.of(-1, 1)) {
            try (MockedStatic<FFmpegUtil> ffmpeg = mockStatic(FFmpegUtil.class, CALLS_REAL_METHODS)) {
                decode(ffmpeg, Set.of(), 9, code);
                IllegalStateException error = assertThrows(
                        IllegalStateException.class,
                        () -> reader.frames("source", probe, work, frame -> {}));
                assertTrue(error.getMessage().contains("index=9"));
                assertTrue(error.getMessage().contains("exitCode=" + code));
            }
        }
    }

    @Test
    void uploadOrLeaseFailureEscapesWithoutBeingTreatedAsMissingFrame() {
        try (MockedStatic<FFmpegUtil> ffmpeg = mockStatic(FFmpegUtil.class, CALLS_REAL_METHODS)) {
            decode(ffmpeg, Set.of(), -1, 0);
            var failure = new IllegalStateException("upload failed or lease lost");
            assertSame(
                    failure,
                    assertThrows(IllegalStateException.class, () -> reader.frames("source", probe, work, frame -> {
                        if (frame.index() == 8) throw failure;
                    })));
            assertFalse(calls.containsKey(9));
            assertEquals(1, calls.get(8));
        }
    }

    @Test
    void reusedSlotsCountTowardTheMinimumWithoutBeingDecodedAgain() throws Exception {
        try (MockedStatic<FFmpegUtil> ffmpeg = mockStatic(FFmpegUtil.class, CALLS_REAL_METHODS)) {
            decode(ffmpeg, Set.of(2, 7), -1, 0);
            var decoded = new ArrayList<Integer>();
            reader.frames("source", probe, work, i -> i == 0 || i == 1, frame -> decoded.add(frame.index()));
            assertEquals(List.of(3, 4, 5, 6, 8, 9), decoded);
            assertFalse(calls.containsKey(0));
            assertFalse(calls.containsKey(1));
        }
    }
}
