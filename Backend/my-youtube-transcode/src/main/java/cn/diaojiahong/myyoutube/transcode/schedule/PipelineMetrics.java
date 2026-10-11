package cn.diaojiahong.myyoutube.transcode.schedule;
import cn.diaojiahong.myyoutube.transcode.mapper.MediaTaskMapper;
import io.micrometer.core.instrument.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
@Component
@RequiredArgsConstructor
public class PipelineMetrics {
    private final MediaTaskMapper tasks;
    private final MeterRegistry metrics;
    private final Map<List<String>,AtomicLong> gauges = new HashMap<>();
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void sample() {
        gauges.values().forEach(v -> v.set(0));
        for (var row : tasks.backlog()) {
            var key=List.of(row.get("phase").toString(),row.get("task_type").toString(),row.get("status").toString());
            var value=gauges.computeIfAbsent(key,k -> {
                var count=new AtomicLong();
                Gauge.builder("media.tasks",count,AtomicLong::get).tags("phase",k.get(0),"type",k.get(1),"status",k.get(2)).register(metrics);
                return count;
            });
            value.set(((Number)row.get("total")).longValue());
        }
    }
}
