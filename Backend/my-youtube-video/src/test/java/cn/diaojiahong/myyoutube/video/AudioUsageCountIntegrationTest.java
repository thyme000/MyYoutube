package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.common.mq.message.PublicationBranch;
import cn.diaojiahong.myyoutube.common.mq.message.PublicationEffectMessage;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.notification.service.IUserNotificationService;
import cn.diaojiahong.myyoutube.search.api.ContentDocPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.domain.po.ContentPublicationEvent;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import cn.diaojiahong.myyoutube.video.mapper.ContentPublicationEventMapper;
import cn.diaojiahong.myyoutube.video.mapper.ContentReviewRecordMapper;
import cn.diaojiahong.myyoutube.video.service.AudioUsageCountService;
import cn.diaojiahong.myyoutube.video.service.PublicationEffectsService;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(AudioUsageCountIntegrationTest.Config.class)
class AudioUsageCountIntegrationTest {
    @Configuration
    @MapperScan(basePackageClasses = AudioMapper.class)
    static class Config {
        @Bean DataSource dataSource() { return ContentTestDatabase.create("audio-usage"); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setMapperLocations(new ClassPathResource("mapper/ContentPublicationEventMapper.xml"));
            return factory.getObject();
        }
        @Bean TransactionTemplate transaction(DataSource source) {
            return new TransactionTemplate(new DataSourceTransactionManager(source));
        }
    }

    @Autowired DataSource source;
    @Autowired AudioMapper audios;
    @Autowired ContentPublicationEventMapper events;
    @Autowired TransactionTemplate transaction;
    JdbcTemplate jdbc;
    AudioUsageCountService counts;
    PublicationEffectsService effects;
    RocketMQTemplate mq;
    ContentDocPort search;

    @BeforeEach void setup() throws Exception {
        jdbc = new JdbcTemplate(source);
        ContentTestDatabase.drop(jdbc, "audio", "content_publication_event");
        String schema = Files.readString(Path.of("../../resources/schema.sql"));
        for (String table : new String[]{"`audio`", "content_publication_event"}) {
            int start = schema.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
            String ddl = schema.substring(start, schema.indexOf(';', start) + 1);
            if (!Boolean.getBoolean("content.integration"))
                ddl = ddl.replace(" CHARACTER SET ascii COLLATE ascii_bin", "").replace(" ENGINE=InnoDB", "");
            jdbc.execute(ddl);
        }
        for (long id : new long[]{20, 21}) {
            audios.insert(new Audio().setId(id).setUploaderId(9L).setTitle("音频")
                    .setRequestId("request-" + id).setRequestFingerprint("a".repeat(64))
                    .setSourceObjectKey("uploads/" + id).setUploadSize(100L).setUploadContentType("audio/wav")
                    .setUploadDeadline(LocalDateTime.now().plusMinutes(40)));
        }
        counts = new AudioUsageCountService(events, audios, transaction);
        mq = mock(RocketMQTemplate.class);
        search = mock(ContentDocPort.class);
        effects = new PublicationEffectsService(events, mock(UserPort.class), search, mock(EsSyncPublisher.class),
                mock(IUserNotificationService.class), mock(PushFanoutPublisher.class), transaction,
                mock(RedissonClient.class), mq, mock(ContentReviewRecordMapper.class), counts);
    }

    void event(long id, long audioId, int delta) {
        events.insert(new ContentPublicationEvent().setId(id).setContentId(id).setAuthorId(9L)
                .setStatus(ContentStatusEnum.PUBLISHED).setDelta(0).setAudioId(audioId).setAudioDelta(delta)
                .setAudioCountDone(false).setUserCountDone(true).setSearchDone(true).setNoticeDone(true).setCompleted(false));
    }

    @Test void mergesDeltasAndDuplicateOrOutOfOrderMessagesDoNotCountAgain() {
        event(1, 20, 1); event(2, 20, 1); event(3, 20, -1);
        effects.apply(new PublicationEffectMessage(3L, PublicationBranch.AUDIO_COUNT));
        effects.apply(new PublicationEffectMessage(1L, PublicationBranch.AUDIO_COUNT));
        effects.apply(new PublicationEffectMessage(3L, PublicationBranch.AUDIO_COUNT));
        assertEquals(1L, audios.selectById(20L).getUsageCount());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_event WHERE audio_count_done=1 AND completed=1", Integer.class));
        assertEquals(1L, audios.selectById(20L).getIndexVersion());
    }

    @Test void oneDeliveryProcessesAtMostTwoHundredEventsAndRecoveryFindsTheRest() {
        for (int id = 1; id <= 201; id++) event(id, 20, 1);
        effects.apply(new PublicationEffectMessage(1L, PublicationBranch.AUDIO_COUNT));
        assertEquals(200L, audios.selectById(20L).getUsageCount());
        assertFalse(events.selectById(201L).getAudioCountDone());
        effects.recover();
        verify(mq).syncSend(MqConstants.TOPIC_PUBLICATION_EFFECT + ":AUDIO_COUNT", new PublicationEffectMessage(201L, PublicationBranch.AUDIO_COUNT));
        effects.apply(new PublicationEffectMessage(201L, PublicationBranch.AUDIO_COUNT));
        assertEquals(201L, audios.selectById(20L).getUsageCount());
        assertTrue(events.selectById(201L).getCompleted());
    }

    @Test void concurrentDeliveriesApplyTheSameBatchOnce() throws Exception {
        for (int id = 1; id <= 10; id++) event(id, 20, 1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> counts.apply(20L));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> counts.apply(20L));
        first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        assertEquals(10L, audios.selectById(20L).getUsageCount());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_publication_event WHERE audio_count_done=0", Integer.class));
    }

    @Test void differentAudioCountsDoNotWaitForEachOtherToCommit() throws Exception {
        event(1, 20, 1); event(2, 21, 1);
        var processed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(tx -> {
            counts.apply(20L); processed.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        }));
        CompletableFuture<Void> second = null;
        try {
            assertTrue(processed.await(5, TimeUnit.SECONDS));
            second = CompletableFuture.runAsync(() -> counts.apply(21L));
            second.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown(); first.get(5, TimeUnit.SECONDS);
            if (second != null) second.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1L, audios.selectById(20L).getUsageCount());
        assertEquals(1L, audios.selectById(21L).getUsageCount());
    }

    @Test void markingFailureRollsBackTheCountAndLeavesTheEventsPending() {
        event(1, 20, 1);
        jdbc.execute("ALTER TABLE content_publication_event ADD CONSTRAINT reject_audio_done CHECK(audio_count_done=0)");
        assertThrows(RuntimeException.class, () -> counts.apply(20L));
        assertEquals(0L, audios.selectById(20L).getUsageCount());
        assertFalse(events.selectById(1L).getAudioCountDone());
        assertFalse(events.selectById(1L).getCompleted());
    }

    @Test void missingAudioDoesNotAcknowledgeAnUnappliedDelta() {
        event(1, 99, 1);
        assertThrows(IllegalStateException.class, () -> counts.apply(99L));
        assertFalse(events.selectById(1L).getAudioCountDone());
    }

    @Test void completingAudioCountDoesNotCompleteOtherPendingBranches() {
        event(1, 20, 1);
        jdbc.update("UPDATE content_publication_event SET search_done=0 WHERE id=1");
        effects.apply(new PublicationEffectMessage(1L, PublicationBranch.AUDIO_COUNT));
        assertTrue(events.selectById(1L).getAudioCountDone());
        assertFalse(events.selectById(1L).getCompleted());
        effects.apply(new PublicationEffectMessage(1L, PublicationBranch.SEARCH));
        assertTrue(events.selectById(1L).getCompleted());
    }

    @Test void otherCompletedBranchesCannotHideThePendingAudioCount() {
        event(1, 20, 1);
        effects.apply(new PublicationEffectMessage(1L, PublicationBranch.SEARCH));
        assertFalse(events.selectById(1L).getCompleted());
        assertEquals(0L, audios.selectById(20L).getUsageCount());
        effects.apply(new PublicationEffectMessage(1L, PublicationBranch.AUDIO_COUNT));
        assertTrue(events.selectById(1L).getCompleted());
    }

    @Test void failedMqWakeupLeavesDurableProgressForTheExistingRecovery() {
        event(1, 20, 1);
        doThrow(new IllegalStateException("MQ unavailable")).when(mq).syncSend(anyString(), any(PublicationEffectMessage.class));
        assertDoesNotThrow(() -> effects.trigger(1L));
        assertFalse(events.selectById(1L).getAudioCountDone());
        reset(mq);
        effects.recover();
        verify(mq).syncSend(MqConstants.TOPIC_PUBLICATION_EFFECT + ":AUDIO_COUNT", new PublicationEffectMessage(1L, PublicationBranch.AUDIO_COUNT));
        effects.apply(new PublicationEffectMessage(1L, PublicationBranch.AUDIO_COUNT));
        assertEquals(1L, audios.selectById(20L).getUsageCount());
    }

    @Test void searchFailureDoesNotBlockTheIndependentAudioCount() {
        event(1, 20, 1);
        jdbc.update("UPDATE content_publication_event SET search_done=0 WHERE id=1");
        doThrow(new IllegalStateException("Search unavailable")).when(search).rebuildContent(1L);
        assertThrows(IllegalStateException.class, () -> effects.apply(new PublicationEffectMessage(1L, PublicationBranch.SEARCH)));
        effects.apply(new PublicationEffectMessage(1L, PublicationBranch.AUDIO_COUNT));
        assertEquals(1L, audios.selectById(20L).getUsageCount());
        assertTrue(events.selectById(1L).getAudioCountDone());
        assertFalse(events.selectById(1L).getCompleted());
        effects.recover();
        verify(mq).syncSend(MqConstants.TOPIC_PUBLICATION_EFFECT + ":SEARCH", new PublicationEffectMessage(1L, PublicationBranch.SEARCH));
        verifyNoMoreInteractions(mq);
    }

    @Test void netZeroBatchIsAcknowledgedWithoutChangingTheCount() {
        event(1, 20, 1); event(2, 20, -1);
        counts.apply(20L);
        assertEquals(0L, audios.selectById(20L).getUsageCount());
        assertTrue(events.selectById(1L).getCompleted());
        assertTrue(events.selectById(2L).getCompleted());
    }

    @Test void finishingAudioProgressDoesNotDecideOverallCompletion() {
        event(1, 20, 1);
        assertEquals(1, events.finishAudioCounts(List.of(1L)));
        assertTrue(events.selectById(1L).getAudioCountDone());
        assertFalse(events.selectById(1L).getCompleted());
    }

    @Test void overallCompletionFailureRollsBackTheCountAndAudioProgress() {
        event(1, 20, 1);
        jdbc.execute("ALTER TABLE content_publication_event ADD CONSTRAINT reject_completed CHECK(completed=0)");
        assertThrows(RuntimeException.class, () -> counts.apply(20L));
        assertEquals(0L, audios.selectById(20L).getUsageCount());
        assertFalse(events.selectById(1L).getAudioCountDone());
        assertFalse(events.selectById(1L).getCompleted());
    }

    @ParameterizedTest @EnumSource(PublicationBranch.class)
    void sharedCompletionRequiresEveryBranch(PublicationBranch pending) {
        event(1, 20, 1); event(2, 20, 1);
        jdbc.update("UPDATE content_publication_event SET audio_count_done=1");
        String column = switch (pending) {
            case COUNT -> "user_count_done";
            case SEARCH -> "search_done";
            case NOTICE -> "notice_done";
            case AUDIO_COUNT -> "audio_count_done";
        };
        jdbc.update("UPDATE content_publication_event SET " + column + "=0 WHERE id=1");
        assertEquals(1, events.completeIfReady(List.of(1L, 2L)));
        assertFalse(events.selectById(1L).getCompleted());
        assertTrue(events.selectById(2L).getCompleted());
        jdbc.update("UPDATE content_publication_event SET " + column + "=1 WHERE id=1");
        assertEquals(1, events.completeIfReady(List.of(1L, 2L)));
        assertTrue(events.selectById(1L).getCompleted());
        assertEquals(0, events.completeIfReady(List.of(1L, 2L)));
    }

    @Test void typedPublicationBranchesPreserveTheirMqCodes() {
        for (PublicationBranch branch : PublicationBranch.values()) {
            var message = new PublicationEffectMessage(1L, branch);
            String json = JsonUtil.toJson(message);
            assertTrue(json.contains("\"branch\":\"" + branch.name() + "\""));
            assertEquals(message, JsonUtil.fromJson(json, PublicationEffectMessage.class));
        }
    }
}
