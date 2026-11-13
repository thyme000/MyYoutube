package cn.diaojiahong.myyoutube.recommend.service.impl;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.recommend.domain.dto.BehaviorDTO;
import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.UserVideoBehavior;
import cn.diaojiahong.myyoutube.recommend.mapper.UserVideoBehaviorMapper;
import cn.diaojiahong.myyoutube.recommend.service.IUserTagPreferenceService;
import cn.diaojiahong.myyoutube.recommend.service.IUserVideoBehaviorService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

import static cn.diaojiahong.myyoutube.common.utils.VideoPlayCountShardUtil.redisHashKey;

/**
 * <p>
 * 用户-视频行为流水表 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
@Service
@RequiredArgsConstructor
public class UserVideoBehaviorServiceImpl extends ServiceImpl<UserVideoBehaviorMapper, UserVideoBehavior> implements IUserVideoBehaviorService {

    private final IUserTagPreferenceService userTagPreferenceService;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void report(BehaviorDTO behaviorDTO) {
        Long userId = Long.valueOf(UserContext.getUserId());
        Long contentId = behaviorDTO.getContentId();

        UserVideoBehavior userVideoBehavior = new UserVideoBehavior();
        userVideoBehavior.setUserId(userId);
        userVideoBehavior.setContentId(contentId);
        userVideoBehavior.setCreatedAt(behaviorDTO.getWatchTime());
        userVideoBehavior.setDurationMs(behaviorDTO.getWatchDuration());
        userVideoBehavior.setEventType(behaviorDTO.getEvent());

        // 事务只圈 DB 写(行为流水 + 标签偏好), Redis 是网络 IO 留在提交后
        transactionTemplate.executeWithoutResult(tx -> {
            save(userVideoBehavior);
            userTagPreferenceService.addBehaviorScore(userId, contentId, behaviorDTO.getEvent());
        });

        // 更新用户看过的内容id
        if (behaviorDTO.getEvent() == EventEnum.DISLIKE) {

            redisTemplate.opsForSet().add(RedisConstants.USER_DISLIKE + userId, contentId.toString());
        } else if (behaviorDTO.getEvent() == EventEnum.PLAY || behaviorDTO.getEvent() == EventEnum.LIKE) {

            redisTemplate.opsForSet().add(RedisConstants.USER_VIEWED + userId, contentId.toString());
            redisTemplate.expire(RedisConstants.USER_VIEWED + userId, RedisConstants.USER_VIEWED_EXPIRE, TimeUnit.DAYS);
        }

        // 是否需要更新播放量
        if (behaviorDTO.getEvent() == EventEnum.PLAY) {

            // 增加播放量：写入分片 Hash，避免单 key 过大（field 为内容 id 字符串）
            redisTemplate.opsForHash().increment(redisHashKey(contentId), String.valueOf(contentId), 1);
        }
    }
}
