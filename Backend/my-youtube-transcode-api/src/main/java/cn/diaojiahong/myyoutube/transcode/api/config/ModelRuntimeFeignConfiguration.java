package cn.diaojiahong.myyoutube.transcode.api.config;

import cn.diaojiahong.myyoutube.common.feign.ModelErrorDecoder;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

public class ModelRuntimeFeignConfiguration {
    @Bean
    public ErrorDecoder modelRuntimeErrorDecoder() {
        return new ModelErrorDecoder(ModelErrorDecoder.Protocol.FASTAPI);
    }
}
