package cn.diaojiahong.myyoutube.transcode.config;

import cn.diaojiahong.myyoutube.common.observability.PipelineObservationConfiguration;
import org.springframework.context.annotation.*;

@Configuration
@Import(PipelineObservationConfiguration.class)
public class PipelineObservationConfig {}
