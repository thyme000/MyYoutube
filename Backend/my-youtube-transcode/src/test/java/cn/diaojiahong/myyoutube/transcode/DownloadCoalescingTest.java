package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.transcode.api.enums.TranscodeTaskStatus;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.transcode.api.dto.*;
import cn.diaojiahong.myyoutube.transcode.chain.dynamic.*;
import cn.diaojiahong.myyoutube.transcode.domain.po.TranscodeTask;
import cn.diaojiahong.myyoutube.transcode.mapper.TranscodeTaskMapper;
import cn.diaojiahong.myyoutube.transcode.mq.listener.TranscodeTaskListener;
import cn.diaojiahong.myyoutube.transcode.mq.message.TranscodeTaskMessage;
import cn.diaojiahong.myyoutube.transcode.service.TranscodeTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DownloadCoalescingTest {
    @Test void simultaneousRequestsAndDuplicateDeliveriesComposeOnlyOnce() throws Exception {
        var service = mock(TranscodeTaskService.class);
        var notifier = mock(TranscodeNotifier.class);
        var chains = mock(SyncChainStepRegistry.class);
        var lock = mock(RLock.class);
        var mutex = new ReentrantLock();
        doAnswer(c -> { mutex.lockInterruptibly(); return null; }).when(lock).lockInterruptibly();
        doAnswer(c -> { mutex.unlock(); return null; }).when(lock).unlock();
        when(service.artifactLock("same-media")).thenReturn(lock);
        var rows = new ConcurrentHashMap<Long, TranscodeTask>();
        for (long i = 1; i <= 8; i++) rows.put(i, new TranscodeTask().setId(i).setTaskKey("same-media").setStatus(TranscodeTaskStatus.PENDING));
        when(service.getById(anyLong())).thenAnswer(c -> rows.get(c.getArgument(0)));
        when(service.updateByIdWithStatus(anyLong(), any(), eq(TranscodeTaskStatus.PENDING))).thenAnswer(c -> {
            var row = rows.get(c.getArgument(0));
            if (!TranscodeTaskStatus.PENDING.equals(row.getStatus())) return false;
            row.setStatus(TranscodeTaskStatus.PROCESSING); return true;
        });
        var count = new AtomicInteger();
        doAnswer(c -> {
            count.incrementAndGet();
            Thread.sleep(30);
            rows.values().forEach(row -> row.setStatus(TranscodeTaskStatus.DONE));
            return null;
        }).when(chains).execute(eq(DynamicTranscodeChain.GROUP), any());
        var listener = new TranscodeTaskListener(service, notifier, chains);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 16; i++) {
                long id = i % 8 + 1;
                futures.add(pool.submit(() -> listener.onMessage(new TranscodeTaskMessage(id))));
            }
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(1, count.get());
            assertTrue(rows.values().stream().allMatch(row -> TranscodeTaskStatus.DONE.equals(row.getStatus())));
        } finally { pool.shutdownNow(); }
    }
    @Test void missingCachedObjectIsExpiredRatherThanReused() {
        var mapper = mock(TranscodeTaskMapper.class);
        var storage = mock(S3StorageUtil.class);
        var service = new TranscodeTaskService(mapper, new ObjectMapper(), mock(RedissonClient.class), storage);
        when(mapper.selectOne(any())).thenReturn(new TranscodeTask().setResultObjectKey("gone.mp4"));
        assertNull(service.validCompleted("key"));
        verify(storage).objectExists("gone.mp4");
        verify(mapper).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
    }
    @Test void availableCacheIsReusedAndBusyComposeDoesNotBlockSubmission() throws Exception {
        var mapper = mock(TranscodeTaskMapper.class);
        var storage = mock(S3StorageUtil.class);
        var redis = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        when(redis.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true, false);
        when(mapper.selectOne(any())).thenReturn(new TranscodeTask().setResultObjectKey("valid.mp4"));
        when(storage.objectExists("valid.mp4")).thenReturn(true);
        var service = new TranscodeTaskService(mapper, new ObjectMapper(), redis, storage);
        var dto = new TranscodeSubmitDTO(); dto.setItems(List.of(new TranscodeItemDTO()));
        assertEquals(TranscodeTaskStatus.DONE, service.submit(dto).getStatus());
        assertEquals(TranscodeTaskStatus.PENDING, service.submit(dto).getStatus());
        verify(lock, times(1)).unlock();
        verify(lock, never()).lockInterruptibly();
    }
}
