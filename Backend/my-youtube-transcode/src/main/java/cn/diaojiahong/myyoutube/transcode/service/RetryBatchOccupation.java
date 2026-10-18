package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.*;
import cn.diaojiahong.myyoutube.transcode.config.PipelineProperties;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaRetryBatch;
import lombok.RequiredArgsConstructor;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RetryBatchOccupation {
    private static final String KEY = "media:retry:active";
    private final RedissonClient redis;
    private final PipelineProperties properties;

    public record Occupation(String requestId, Long actorId, List<Long> taskIds, Batch batch) {}

    public RLock admissionLock() { return redis.getLock("media:retry:admission"); }
    private RBucket<String> bucket() { return redis.getBucket(KEY, StringCodec.INSTANCE); }
    public Occupation current() {
        String value = bucket().get();
        return value == null ? null : JsonUtil.fromJson(value, Occupation.class);
    }
    public void publish(MediaRetryBatch row, List<Long> ids, Batch view) {
        bucket().setIfAbsent(JsonUtil.toJson(new Occupation(row.getRequestId(), row.getActorId(), ids, view)),
                Duration.ofSeconds(properties.getRetryOccupationSeconds()));
    }
    public void update(Batch view) {
        String next = "RUNNING".equals(view.status()) ? JsonUtil.toJson(view) : "";
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                "local value=redis.call('get',KEYS[1]); if not value then return 0 end; "
                        + "local current=cjson.decode(value); if current.batch.batchId ~= ARGV[1] then return 0 end; "
                        + "if ARGV[2] == '' then redis.call('del',KEYS[1]); "
                        + "else current.batch=cjson.decode(ARGV[2]); redis.call('set',KEYS[1],cjson.encode(current),'KEEPTTL') end; return 1",
                RScript.ReturnType.INTEGER, List.of(KEY), view.batchId().toString(), next);
    }
}
