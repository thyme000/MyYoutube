package cn.diaojiahong.myyoutube.transcode.schedule;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.transcode.api.vo.TranscodeTaskVO;
import cn.diaojiahong.myyoutube.transcode.domain.po.TranscodeTask;
import cn.diaojiahong.myyoutube.transcode.mapper.TranscodeTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 成片缓存 LRU 清理: Redis zset 记录 taskKey 最近访问时间,
 * 超过容量上限的最旧条目从对象存储删除并落 EXPIRED, 下次下载会重新生成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranscodeLruCleaner {

    private static final long MAX_ENTRIES = 100;

    private final StringRedisTemplate redisTemplate;
    private final TranscodeTaskMapper taskMapper;
    private final S3StorageUtil storageUtil;

    @Scheduled(fixedDelay = 3600_000, initialDelay = 120_000)
    public void clean() {
        try {
            Long size = redisTemplate.opsForZSet().zCard(RedisConstants.TRANSCODE_LRU);
            if (size == null || size <= MAX_ENTRIES) {
                return;
            }
            long evict = size - MAX_ENTRIES;
            Set<String> oldest = redisTemplate.opsForZSet().range(RedisConstants.TRANSCODE_LRU, 0, evict - 1);
            if (oldest == null || oldest.isEmpty()) {
                return;
            }
            for (String taskKey : oldest) {
                redisTemplate.opsForZSet().remove(RedisConstants.TRANSCODE_LRU, taskKey);
                for (TranscodeTask task : taskMapper.selectList(new LambdaQueryWrapper<TranscodeTask>()
                        .eq(TranscodeTask::getTaskKey, taskKey)
                        .eq(TranscodeTask::getStatus, TranscodeTaskVO.STATUS_DONE))) {
                    if (task.getResultObjectKey() != null && !task.getResultObjectKey().isEmpty()) {
                        storageUtil.deleteObjectQuietly(task.getResultObjectKey());
                    }
                    // 直接删行最干净: 下次提交查不到 DONE, 会重新转码
                    taskMapper.deleteById(task.getId());
                }
            }
            log.info("成片缓存 LRU 清理完成, 本轮淘汰 {} 个 taskKey", oldest.size());
        } catch (Exception e) {
            log.warn("成片缓存 LRU 清理失败", e);
        }
    }
}
