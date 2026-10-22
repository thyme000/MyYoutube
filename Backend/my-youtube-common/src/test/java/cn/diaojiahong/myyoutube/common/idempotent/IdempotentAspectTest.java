package cn.diaojiahong.myyoutube.common.idempotent;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdempotentAspectTest {
    final IdempotentGuard guard = mock(IdempotentGuard.class);
    final IdempotentGuard.Lease lease = mock(IdempotentGuard.Lease.class);
    final Operations target = new Operations();
    Operations proxy;

    @BeforeEach void setup() {
        var factory = new AspectJProxyFactory(target);
        factory.addAspect(new IdempotentAspect(guard));
        proxy = factory.getProxy();
        when(guard.acquire(anyString(), anyLong())).thenReturn(lease);
        when(lease.acquired()).thenReturn(true);
    }

    @Test void completedSkipDoesNotExecuteOrReleaseAnotherExecution() {
        when(lease.acquired()).thenReturn(false);
        when(lease.cachedValue()).thenReturn(IdempotentGuard.DONE);
        proxy.skip(7L);
        assertEquals(0, target.calls);
        verify(lease, never()).complete(anyString(), anyLong());
    }

    @Test void runningSkipPropagatesRetryInsteadOfAcknowledging() {
        when(guard.acquire(anyString(), anyLong())).thenThrow(new BizException(ErrorCode.INVALID_ARGUMENT, "busy"));
        assertThrows(BizException.class, () -> proxy.skip(7L));
        assertEquals(0, target.calls);
    }

    @Test void completionCacheStartsAfterSuccessfulExecution() {
        proxy.skip(7L);
        assertEquals(1, target.calls);
        verify(guard).acquire("test:7", 2);
        verify(lease).complete(IdempotentGuard.DONE, 9);
        verify(lease).close();
    }

    @Test void executionFailureReleasesWithoutCompletion() {
        assertThrows(IllegalStateException.class, () -> proxy.fail(7L));
        verify(lease, never()).complete(anyString(), anyLong());
        verify(lease).close();
    }

    @Test void replayReturnsCachedResultWithoutExecuting() {
        when(lease.acquired()).thenReturn(false);
        when(lease.cachedValue()).thenReturn("\"cached\"");
        assertEquals("cached", proxy.replay(7L));
        assertEquals(0, target.calls);
    }

    @Test void replayStoresSuccessfulResultWhenCachingIsEnabled() {
        assertEquals("new", proxy.replay(7L));
        verify(lease).complete("\"new\"", 300);
    }

    @Test void replayWithoutCachingDoesNotSerializeTheReturnValue() {
        assertSame(Thread.currentThread(), proxy.replayExecutionOnly(7L));
        assertEquals(1, target.calls);
        verify(lease).complete(IdempotentGuard.DONE, 0);
    }

    @Test void throwPolicyStillRejectsCompletedRequests() {
        when(lease.acquired()).thenReturn(false);
        when(lease.cachedValue()).thenReturn(IdempotentGuard.DONE);
        assertThrows(BizException.class, () -> proxy.reject(7L));
        assertEquals(0, target.calls);
    }

    @Test void zeroTtlUsesExecutionOnlyOccupancy() {
        proxy.executionOnly(7L);
        verify(lease).complete(IdempotentGuard.DONE, 0);
    }

    @Test void unresolvedKeyIsRejectedBeforeAcquisition() {
        assertThrows(IllegalStateException.class, () -> proxy.skip(null));
        verifyNoInteractions(guard);
    }

    static class Operations {
        int calls;

        @Idempotent(key = "#id == null ? null : 'test:' + #id", ttl = 9, leaseSeconds = 2, policy = DuplicatePolicy.SKIP)
        public void skip(Long id) { calls++; }

        @Idempotent(key = "'test:' + #id", policy = DuplicatePolicy.REPLAY)
        public String replay(Long id) { calls++; return "new"; }

        @Idempotent(key = "'test:' + #id")
        public void reject(Long id) { calls++; }

        @Idempotent(key = "'test:' + #id")
        public void fail(Long id) { throw new IllegalStateException("failed"); }

        @Idempotent(key = "'test:' + #id", ttl = 0)
        public void executionOnly(Long id) { calls++; }

        @Idempotent(key = "'test:' + #id", ttl = 0, policy = DuplicatePolicy.REPLAY)
        public Thread replayExecutionOnly(Long id) { calls++; return Thread.currentThread(); }
    }
}
