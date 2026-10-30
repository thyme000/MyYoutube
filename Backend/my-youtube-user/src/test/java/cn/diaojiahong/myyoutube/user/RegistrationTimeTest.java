package cn.diaojiahong.myyoutube.user;

import cn.diaojiahong.myyoutube.auth.domain.dto.RegisterRequestDTO;
import cn.diaojiahong.myyoutube.auth.service.impl.AuthServiceImpl;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.common.utils.BusinessTime;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistrationTimeTest {
    @Test
    void registrationStoresCurrentInstantAsBusinessWallTime() {
        IUserService users = mock(IUserService.class);
        EsSyncPublisher publisher = mock(EsSyncPublisher.class);
        when(users.save(any(User.class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            user.setId(9L);
            return true;
        });
        var auth = new AuthServiceImpl(users, mock(StringRedisTemplate.class), mock(RocketMQTemplate.class),
                publisher, mock(SyncChainStepRegistry.class));
        var request = new RegisterRequestDTO();
        request.setUsername("tester");
        request.setPassword("Password123!");
        Instant before = Instant.now();
        auth.register(request);
        Instant after = Instant.now();
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        Instant created = BusinessTime.toInstant(saved.getValue().getCreateTime());
        assertFalse(created.isBefore(before));
        assertFalse(created.isAfter(after));
        verify(publisher).syncUserDoc(9L);
    }
}
