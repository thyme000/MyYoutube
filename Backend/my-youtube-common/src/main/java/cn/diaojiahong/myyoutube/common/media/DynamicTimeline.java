package cn.diaojiahong.myyoutube.common.media;

import java.util.ArrayList;
import java.util.List;

public final class DynamicTimeline {
    private DynamicTimeline() {}

    public record Schedule(List<Long> startsMs, long exportDurationMs) {
        public Schedule { startsMs = List.copyOf(startsMs); }
    }

    public static Schedule schedule(List<Long> durationsMs, long fadeMs) {
        if (durationsMs == null || durationsMs.isEmpty() || fadeMs < 0)
            throw new IllegalArgumentException("图片与转场时长无效");
        var starts = new ArrayList<Long>(durationsMs.size());
        long end = 0;
        try {
            for (int i = 0; i < durationsMs.size(); i++) {
                Long duration = durationsMs.get(i);
                if (duration == null || duration <= fadeMs)
                    throw new IllegalArgumentException("图片时长必须大于转场时长");
                long start = i == 0 ? 0 : Math.subtractExact(end, fadeMs);
                starts.add(start);
                end = Math.addExact(start, duration);
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("图片总时长超出范围", e);
        }
        return new Schedule(starts, end);
    }
}
