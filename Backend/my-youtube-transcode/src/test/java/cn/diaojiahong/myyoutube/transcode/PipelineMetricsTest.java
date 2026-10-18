package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.transcode.mapper.MediaTaskMapper;
import cn.diaojiahong.myyoutube.transcode.schedule.PipelineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineMetricsTest {
    @Test void inactivePipelineStockExcludesRunningWorkAndRecovers() {
        var jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:pipeline-metrics;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE media_pipeline(id BIGINT,status VARCHAR(32),received BOOLEAN,completed_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE media_task(id BIGINT,pipeline_id BIGINT,phase VARCHAR(16),status VARCHAR(16),ready_at TIMESTAMP)");
        jdbc.update("INSERT INTO media_pipeline VALUES(1,'PROCESSING',FALSE,NULL),(2,'PROCESSING',FALSE,NULL)");
        jdbc.update("INSERT INTO media_task VALUES(1,1,'CONTENT','PENDING',NULL),(2,2,'CONTENT','RUNNING',NULL)");
        var tasks = mock(MediaTaskMapper.class); when(tasks.backlog()).thenReturn(List.of());
        var registry = new SimpleMeterRegistry(); var sampler = new PipelineMetrics(tasks,jdbc,registry,true);
        sampler.sample(); assertEquals(1, registry.get("media.pipeline.inactive.lowerbound").gauge().value());
        jdbc.update("UPDATE media_pipeline SET status='COMPLETED' WHERE id=1");
        sampler.sample(); assertEquals(0, registry.get("media.pipeline.inactive.lowerbound").gauge().value());
    }
    @Test void failureDoesNotClearStocksAndDisabledAgeProbesDoNoSql() {
        var tasks = mock(MediaTaskMapper.class); var jdbc = mock(JdbcTemplate.class); var registry = new SimpleMeterRegistry();
        var sampler = new PipelineMetrics(tasks, jdbc, registry, false);
        when(tasks.backlog()).thenReturn(List.of(Map.of("phase","CONTENT","task_type","ASR","status","READY","total",3L)))
                .thenThrow(new IllegalStateException()).thenReturn(List.of());
        sampler.sample(); assertEquals(3, registry.get("media.tasks").gauge().value());
        sampler.sample(); assertEquals(3, registry.get("media.tasks").gauge().value());
        assertEquals(0, registry.get("pipeline.metrics.sample.success").gauge().value());
        sampler.sample(); assertEquals(0, registry.get("media.tasks").gauge().value());
        assertTrue(Double.isNaN(registry.get("media.ready.oldest.seconds").gauge().value()));
        verifyNoInteractions(jdbc);
    }
}
