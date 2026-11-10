package cn.diaojiahong.myyoutube.user.port;

import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.ReceivedLikeUpdate;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.user.cache.UserSummaryCache;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * UserPort 本地实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserPortAdapter implements UserPort {

    private final cn.diaojiahong.myyoutube.user.service.IUserService userService;
    private final UserSummaryCache summaryCache;
    private final EsSyncPublisher esSyncPublisher;

    @Override
    public UserSummary getUser(Long id) {
        return summaryCache.get(id, uid -> {
            User user = userService.getById(uid);
            return user == null ? null : toSummary(user);
        });
    }

    @Override
    public List<UserSummary> listByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        return summaryCache.listByIds(ids, missIds -> userService.listByIds(missIds).stream()
                .map(UserPortAdapter::toSummary).collect(Collectors.toList()));
    }

    @Override
    public List<Long> overwriteReceivedLikes(List<ReceivedLikeUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        for (ReceivedLikeUpdate update : updates) {
            userIds.add(update.userId());
        }

        Map<Long, User> byId = new HashMap<>();
        for (User user : userService.listByIds(userIds)) {
            byId.put(user.getId(), user);
        }

        List<Long> accepted = new ArrayList<>();
        LinkedHashSet<Long> toPublish = new LinkedHashSet<>();

        for (ReceivedLikeUpdate update : updates) {
            User existing = byId.get(update.userId());
            if (existing == null) {
                continue;
            }

            long currentVersion = existing.getLikeVersion() == null ? 0L : existing.getLikeVersion();
            try {
                if (currentVersion < update.version()) {
                    boolean updated = userService.lambdaUpdate()
                            .eq(User::getId, update.userId())
                            .lt(User::getLikeVersion, update.version())
                            .set(User::getLikeCount, update.likeCount())
                            .set(User::getLikeVersion, update.version())
                            .update();
                    if (updated) {
                        toPublish.add(update.userId());
                    }
                } else if (currentVersion == update.version()) {
                    toPublish.add(update.userId());
                }
                accepted.add(update.userId());
            } catch (RuntimeException e) {
                log.warn("获赞覆盖失败, 本条跳过, userId={}, version={}", update.userId(), update.version(), e);
            }
        }

        for (Long userId : toPublish) {
            publishReceivedLike(userId);
        }
        return accepted;
    }

    private void publishReceivedLike(long userId) {
        summaryCache.evict(userId);
        esSyncPublisher.syncUserDoc(userId);
    }

    private static UserSummary toSummary(User user) {
        UserSummary s = new UserSummary();
        BeanUtil.copyProperties(user, s);
        return s;
    }
}
