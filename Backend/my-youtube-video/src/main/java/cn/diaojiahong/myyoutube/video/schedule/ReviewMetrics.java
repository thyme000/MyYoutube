package cn.diaojiahong.myyoutube.video.schedule;
import cn.diaojiahong.myyoutube.video.mapper.*;
import cn.diaojiahong.myyoutube.video.domain.po.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.micrometer.core.instrument.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;
@Component
public class ReviewMetrics {
    private final ContentUnderstandingMapper rows;
    private final ContentPublicationEventMapper events;
    private final AtomicLong automatic=new AtomicLong(),manual=new AtomicLong(),pendingEffects=new AtomicLong();
    public ReviewMetrics(ContentUnderstandingMapper rows,ContentPublicationEventMapper events,MeterRegistry metrics) {
        this.rows=rows; this.events=events;
        Gauge.builder("media.review.queue",automatic,AtomicLong::get).tag("mode","automatic").register(metrics);
        Gauge.builder("media.review.queue",manual,AtomicLong::get).tag("mode","manual").register(metrics);
        Gauge.builder("media.publication.pending",pendingEffects,AtomicLong::get).register(metrics);
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void sample() {
        automatic.set(rows.selectCount(new LambdaQueryWrapper<ContentUnderstanding>().in(ContentUnderstanding::getState,"AUTO_PENDING","AUTO_RUNNING")));
        manual.set(rows.selectCount(new LambdaQueryWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getState,"MANUAL_PENDING")));
        pendingEffects.set(events.selectCount(new LambdaQueryWrapper<ContentPublicationEvent>().eq(ContentPublicationEvent::getCompleted,false)));
    }
}
