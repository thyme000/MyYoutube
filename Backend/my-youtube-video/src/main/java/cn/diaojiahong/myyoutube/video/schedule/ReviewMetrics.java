package cn.diaojiahong.myyoutube.video.schedule;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.monitoring.SampleHealth;
import cn.diaojiahong.myyoutube.video.mapper.*;
import cn.diaojiahong.myyoutube.video.domain.po.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.micrometer.core.instrument.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.*;
import java.time.*;
import java.time.Clock;

@Component
public class ReviewMetrics {
    private static final Logger log = LoggerFactory.getLogger(ReviewMetrics.class);
    private final ContentUnderstandingMapper rows;
    private final ContentPublicationEventMapper events;
    private final JdbcTemplate jdbc;
    private final SampleHealth health;
    private final boolean oldestEnabled;
    private volatile Stock stock;

    record Stock(long automatic, long manual, long pending, Instant oldest) {
    }

    public ReviewMetrics(
            ContentUnderstandingMapper rows,
            ContentPublicationEventMapper events,
            JdbcTemplate jdbc,
            MeterRegistry metrics,
            @org.springframework.beans.factory.annotation.Value("${my-youtube.observability.monitoring.enabled:false}") boolean oldestEnabled) {
        this.rows = rows;
        this.events = events;
        this.jdbc = jdbc;
        this.oldestEnabled = oldestEnabled;
        health = new SampleHealth(metrics, Clock.systemUTC(), "review");
        Gauge.builder(MetricNames.MEDIA_REVIEW_QUEUE, this, m -> m.stock == null ? Double.NaN : m.stock.automatic())
                .tag("mode", "automatic")
                .register(metrics);
        Gauge.builder(MetricNames.MEDIA_REVIEW_QUEUE, this, m -> m.stock == null ? Double.NaN : m.stock.manual())
                .tag("mode", "manual")
                .register(metrics);
        Gauge.builder(
                        MetricNames.MEDIA_PUBLICATION_PENDING,
                        this,
                        m -> m.stock == null ? Double.NaN : m.stock.pending())
                .register(metrics);
        Gauge.builder(
                MetricNames.MEDIA_PUBLICATION_OLDEST_SECONDS,
                this,
                m -> !m.oldestEnabled || m.stock == null ? Double.NaN : m.stock.oldest() == null ? 0
                : Math.max(0, Duration.between(m.stock.oldest(), Instant.now()).toMillis() / 1000d)).register(metrics);
    }

    @Scheduled(fixedDelay=30000, initialDelay=30000)
    public void sample() {
        try {
            long automatic = rows.selectCount(new LambdaQueryWrapper<ContentUnderstanding>().in(
                    ContentUnderstanding::getState,
                    "AUTO_PENDING",
                    "AUTO_RUNNING"));
            long manual = rows.selectCount(new LambdaQueryWrapper<ContentUnderstanding>().eq(
                    ContentUnderstanding::getState,
                    "MANUAL_PENDING"));
            long pending = events.selectCount(new LambdaQueryWrapper<ContentPublicationEvent>().eq(
                    ContentPublicationEvent::getCompleted,
                    false));
            List<Instant> oldest = oldestEnabled ? jdbc.query("SELECT TIMESTAMPDIFF(SECOND,create_time,CURRENT_TIMESTAMP) FROM content_publication_event WHERE completed=0 ORDER BY create_time,id LIMIT 1",
                    (rs, i) -> Instant.now().minusSeconds(Math.max(0,rs.getLong(1)))) : java.util.List.<Instant>of();
            stock = new Stock(automatic, manual, pending, oldest.isEmpty() ? null : oldest.get(0));
            health.success();
        } catch (Exception error) {
            health.failure();
            log.warn("Review metrics retain previous sample errorType={}", error.getClass().getSimpleName());
        }
    }
}
