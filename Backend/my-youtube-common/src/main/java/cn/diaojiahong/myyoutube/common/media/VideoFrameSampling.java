package cn.diaojiahong.myyoutube.common.media;

import java.util.ArrayList;
import java.util.List;

public final class VideoFrameSampling {
    public static final long INTERVAL_MS = 5000;
    public static final int DEFAULT_MAX_FRAMES = 10;
    public static final int MAX_FRAMES = 64;

    private VideoFrameSampling() {
    }

    public static final class Rejected extends IllegalArgumentException {
        private final String reason;

        private Rejected(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    public record Plan(int targetCount, int minimumCount, int minSuccessPercent) {
        public Plan {
            if (targetCount < 1 || targetCount > MAX_FRAMES || minSuccessPercent < 1 || minSuccessPercent > 100
                    || minimumCount != (targetCount * minSuccessPercent + 99) / 100) {
                throw new IllegalArgumentException("视频采样计划无效");
            }
        }

        public List<Integer> missing(List<Integer> indices) {
            boolean[] present = new boolean[targetCount];
            int previous = -1;
            for (int index : indices) {
                if (index <= previous || index < 0 || index >= targetCount) {
                    throw new IllegalArgumentException("视频采样索引重复、越界或无序");
                }
                present[index] = true;
                previous = index;
            }

            var missing = new ArrayList<Integer>();
            for (int i = 0; i < targetCount; i++) {
                if (!present[i]) {
                    missing.add(i);
                }
            }
            return List.copyOf(missing);
        }

        public void validate(List<Integer> indices) {
            List<Integer> missing = missing(indices);
            if (indices.size() < minimumCount) {
                throw new Rejected("INSUFFICIENT_FRAMES", "视频有效帧不足: target=" + targetCount + ", minimum=" + minimumCount
                        + ", actual=" + indices.size() + ", missing=" + missing);
            }

            for (int i = 1; i < missing.size(); i++) {
                if (missing.get(i) == missing.get(i - 1) + 1) {
                    throw new Rejected("COVERAGE_GAP", "视频采样连续缺帧: missing=" + missing);
                }
            }
        }
    }

    public static Plan plan(int targetCount, int minSuccessPercent) {
        return new Plan(targetCount, (targetCount * minSuccessPercent + 99) / 100, minSuccessPercent);
    }

    public static int count(long durationMs, long availableFrames) {
        return count(durationMs, availableFrames, INTERVAL_MS, DEFAULT_MAX_FRAMES);
    }

    public static int count(long durationMs, long availableFrames, long intervalMs, int maxFrames) {
        if (durationMs <= 0) {
            throw new IllegalArgumentException("视频采样时长无效");
        }

        if (intervalMs <= 0 || maxFrames < 1 || maxFrames > MAX_FRAMES) {
            throw new IllegalArgumentException("视频采样配置无效");
        }

        long count = Math.min(maxFrames, 1 + (durationMs - 1) / intervalMs);
        return (int) (availableFrames > 0 ? Math.min(count, availableFrames) : count);
    }

    public static double ratio(int index, int count) {
        if (count < 1 || count > MAX_FRAMES || index < 0 || index >= count) {
            throw new IllegalArgumentException("视频采样索引无效");
        }

        return count == 1 ? 0.5 : index / (count - 1.0);
    }
}
