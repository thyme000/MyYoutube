package cn.diaojiahong.myyoutube.user;

import cn.diaojiahong.myyoutube.common.schedule.ScheduleRoundLock;
import cn.diaojiahong.myyoutube.user.schedule.UserCheckInStatisticsJob;
import cn.diaojiahong.myyoutube.user.service.UserCheckInStatisticsService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserCheckInStatisticsJobTest {
    private final UserCheckInStatisticsService statistics = mock(UserCheckInStatisticsService.class);

    @Test
    void skipsDatabaseWorkWhenAnotherInstanceHoldsTheLock() throws InterruptedException {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(client.getLock(UserCheckInStatisticsJob.LOCK_NAME)).thenReturn(lock);
        when(lock.tryLock(0, ScheduleRoundLock.LEASE_SECONDS, TimeUnit.SECONDS)).thenReturn(false);
        var beans = new StaticListableBeanFactory(Map.of("redissonClient", client));
        var roundLock = new ScheduleRoundLock(beans.getBeanProvider(RedissonClient.class));

        new UserCheckInStatisticsJob(roundLock, statistics).archivePreviousMonth();

        verify(lock).tryLock(0, ScheduleRoundLock.LEASE_SECONDS, TimeUnit.SECONDS);
        verifyNoInteractions(statistics);
    }
}
