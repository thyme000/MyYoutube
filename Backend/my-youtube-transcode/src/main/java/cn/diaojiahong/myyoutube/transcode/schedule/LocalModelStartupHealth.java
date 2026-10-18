package cn.diaojiahong.myyoutube.transcode.schedule;

import cn.diaojiahong.myyoutube.transcode.service.LocalModelServices;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;

@Component("localModelStartup")
@RequiredArgsConstructor
public class LocalModelStartupHealth implements HealthIndicator {
    private final LocalModelServices models;
    @Override public Health health() {
        return Health.up().withDetail("startupSnapshot",models.startupStates()).build();
    }
}
