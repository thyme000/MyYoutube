package cn.diaojiahong.myyoutube.video.config;
import feign.Retryer;
import org.springframework.context.annotation.*;
@Configuration
public class FeignRetryConfiguration {
    @Bean public Retryer retryer() { return Retryer.NEVER_RETRY; }
}
