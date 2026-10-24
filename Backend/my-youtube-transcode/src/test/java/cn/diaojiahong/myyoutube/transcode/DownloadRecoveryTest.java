package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.transcode.api.enums.TranscodeTaskStatus;
import cn.diaojiahong.myyoutube.transcode.controller.InternalTranscodeController;
import cn.diaojiahong.myyoutube.transcode.domain.po.TranscodeTask;
import cn.diaojiahong.myyoutube.transcode.mapper.TranscodeTaskMapper;
import cn.diaojiahong.myyoutube.transcode.schedule.DownloadTaskDispatcher;
import cn.diaojiahong.myyoutube.transcode.service.TranscodeTaskService;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.redisson.api.RLock;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DownloadRecoveryTest {
    JdbcTemplate jdbc;
    TranscodeTaskMapper rows;
    TranscodeTaskService tasks;
    RocketMQTemplate mq;
    DownloadTaskDispatcher dispatcher;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:download-recovery;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE transcode_task(id BIGINT PRIMARY KEY,task_key VARCHAR(64),status VARCHAR(16),next_dispatch_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        var bean = new MybatisSqlSessionFactoryBean(); bean.setDataSource(ds);
        var factory = bean.getObject(); factory.getConfiguration().addMapper(TranscodeTaskMapper.class);
        rows = new SqlSessionTemplate(factory).getMapper(TranscodeTaskMapper.class);
        tasks = mock(TranscodeTaskService.class); mq = mock(RocketMQTemplate.class);
        var lock = mock(RLock.class); when(lock.tryLock()).thenReturn(true);
        when(tasks.artifactLock(anyString())).thenReturn(lock);
        dispatcher = new DownloadTaskDispatcher(rows, tasks, mq);
    }
    void insert(long id, String key, String status) {
        jdbc.update("INSERT INTO transcode_task(id,task_key,status) VALUES(?,?,?)", id,key,status);
    }
    void due() { jdbc.update("UPDATE transcode_task SET next_dispatch_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)"); }

    @Test void downloadStatusPersistsAsItsCodeAndSupportsConditionalUpdates() {
        rows.insert(new TranscodeTask().setId(1L).setTaskKey("status").setStatus(TranscodeTaskStatus.PENDING));
        var previous = TranscodeTaskStatus.PENDING;
        for (var status : TranscodeTaskStatus.values()) {
            assertEquals(1, rows.update(null, new LambdaUpdateWrapper<TranscodeTask>()
                    .eq(TranscodeTask::getId, 1L).eq(TranscodeTask::getStatus, previous)
                    .set(TranscodeTask::getStatus, status)));
            assertEquals(status.getCode(), jdbc.queryForObject("SELECT status FROM transcode_task WHERE id=1", String.class));
            var saved = rows.selectOne(new LambdaQueryWrapper<TranscodeTask>()
                    .select(TranscodeTask::getId, TranscodeTask::getStatus).eq(TranscodeTask::getId, 1L));
            assertEquals(status, saved.getStatus());
            if (status == TranscodeTaskStatus.PENDING || status == TranscodeTaskStatus.PROCESSING)
                assertEquals(status, rows.due().get(0).getStatus());
            else assertTrue(rows.due().isEmpty());
            previous = status;
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = TranscodeTaskStatus.class, names = {"DONE", "EXPIRED"})
    void expiredResultIsReportedAsFailed(TranscodeTaskStatus status) {
        when(tasks.getById(1L)).thenReturn(new TranscodeTask().setId(1L).setTaskKey("gone").setStatus(status));
        var controller = new InternalTranscodeController(tasks, dispatcher, mock(StringRedisTemplate.class));
        var view = controller.getTask(1L);
        assertEquals(TranscodeTaskStatus.FAILED, view.getStatus());
        assertEquals("成片已过期，请重新下载", view.getErrorMessage());
    }

    @Test void lostInitialSendIsAcceptedAndRecoveredWithoutAnotherRequest() throws Exception {
        insert(1,"key","PENDING");
        var dto = new cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeSubmitDTO();
        dto.setUserId(9L); dto.setItems(java.util.List.of(new cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeItemDTO()));
        when(tasks.submit(dto)).thenReturn(new TranscodeTask().setId(1L).setStatus(TranscodeTaskStatus.PENDING));
        doThrow(new IllegalStateException("broker unavailable")).when(mq).syncSend(anyString(), any(Object.class));
        var controller = new InternalTranscodeController(tasks,dispatcher,mock(StringRedisTemplate.class));
        assertEquals(1L,controller.submitTask(dto).getTaskId());
        dispatcher.recover(); verify(mq,times(1)).syncSend(anyString(),any(Object.class));
        due(); reset(mq); dispatcher.recover();
        verify(mq).syncSend(anyString(),any(Object.class));
    }
    @Test void twoReconcilersReserveOnlyOneDelivery() throws Exception {
        insert(1,"key","PROCESSING");
        var other = new DownloadTaskDispatcher(rows,tasks,mq);
        CompletableFuture.allOf(CompletableFuture.runAsync(dispatcher::recover),CompletableFuture.runAsync(other::recover)).get(5,TimeUnit.SECONDS);
        verify(mq,times(1)).syncSend(anyString(),any(Object.class));
    }
    @Test void busyFirstPageCannotStarveLaterTasks() {
        for(long id=1;id<=100;id++) insert(id,"busy","PROCESSING");
        insert(101,"free","PENDING");
        var busy = mock(RLock.class); when(tasks.artifactLock("busy")).thenReturn(busy);
        dispatcher.recover(); verifyNoInteractions(mq);
        dispatcher.recover(); verify(mq,times(1)).syncSend(anyString(),any(Object.class));
        assertEquals(0,rows.due().size());
    }
    @Test void terminalTasksAreNeverResubmitted() {
        for(String status:java.util.List.of("DONE","FAILED","EXPIRED")) insert(status.hashCode(),status,status);
        due(); dispatcher.recover(); verifyNoInteractions(mq,tasks);
    }
}
