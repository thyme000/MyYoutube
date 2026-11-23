package cn.diaojiahong.myyoutube.recommend.service;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.ContentAccessService;
import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.ContentDislike;
import cn.diaojiahong.myyoutube.recommend.domain.po.UserContentBehavior;
import cn.diaojiahong.myyoutube.recommend.mapper.ContentDislikeMapper;
import cn.diaojiahong.myyoutube.recommend.mapper.UserContentBehaviorMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ContentDislikeService {
    private static final Duration COOLDOWN = Duration.ofDays(60);

    private final ContentAccessService access;
    private final ContentDislikeMapper dislikes;
    private final UserContentBehaviorMapper behaviors;
    private final IUserTagPreferenceService preferences;

    @Transactional
    @ObservedBusiness(BusinessOperation.DISLIKE_CONTENT)
    public long mark(long userId, long contentId, Long watchDuration) {
        Content content = access.readable(contentId, userId);
        // 推荐流已经排除作者本人的作品。落库只会把作者自己的标签偏好打成负分。
        if (Objects.equals(content.getAuthorId(), userId)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "不能对自己的内容标记不感兴趣");
        }

        long now = System.currentTimeMillis();
        ContentDislike current = dislikes.selectOne(contentQuery(userId, contentId));
        if (current != null && current.getExpiresAt() > now) {
            return current.getExpiresAt();
        }

        long expiresAt = now + COOLDOWN.toMillis();
        if (current == null) {
            ContentDislike dislike = new ContentDislike();
            dislike.setUserId(userId);
            dislike.setContentId(contentId);
            dislike.setExpiresAt(expiresAt);
            try {
                dislikes.insert(dislike);
            } catch (DuplicateKeyException concurrentInsert) {
                return latestExpiry(userId, contentId);
            }
        } else {
            int changed = dislikes.update(Wrappers.<ContentDislike>lambdaUpdate()
                    .eq(ContentDislike::getId, current.getId())
                    .le(ContentDislike::getExpiresAt, now)
                    .set(ContentDislike::getExpiresAt, expiresAt));
            if (changed == 0) {
                return latestExpiry(userId, contentId);
            }
        }

        behaviors.insert(new UserContentBehavior()
                .setUserId(userId)
                .setContentId(contentId)
                .setDurationMs(watchDuration)
                .setEventType(EventEnum.DISLIKE));
        preferences.addBehaviorScore(userId, contentId, EventEnum.DISLIKE);
        return expiresAt;
    }

    public Long activeExpiry(long userId, long contentId) {
        access.readable(contentId, userId);
        ContentDislike dislike = dislikes.selectOne(contentQuery(userId, contentId)
                .select(ContentDislike::getExpiresAt)
                .gt(ContentDislike::getExpiresAt, System.currentTimeMillis()));
        return dislike == null ? null : dislike.getExpiresAt();
    }

    public List<Long> activeContentIds(long userId) {
        return dislikes.selectList(Wrappers.<ContentDislike>lambdaQuery()
                        .select(ContentDislike::getContentId)
                        .eq(ContentDislike::getUserId, userId)
                        .gt(ContentDislike::getExpiresAt, System.currentTimeMillis()))
                .stream()
                .map(ContentDislike::getContentId)
                .toList();
    }

    private long latestExpiry(long userId, long contentId) {
        // 并发写入后用当前读获取到期时间，避免重复读到事务开始时的快照。
        ContentDislike dislike = dislikes.selectOne(contentQuery(userId, contentId)
                .select(ContentDislike::getExpiresAt)
                .last("FOR SHARE"));
        return dislike.getExpiresAt();
    }

    private LambdaQueryWrapper<ContentDislike> contentQuery(long userId, long contentId) {
        return Wrappers.<ContentDislike>lambdaQuery()
                .eq(ContentDislike::getUserId, userId)
                .eq(ContentDislike::getContentId, contentId);
    }
}
