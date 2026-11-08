package cn.diaojiahong.myyoutube.user.port;

import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.user.cache.UserSummaryCache;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * UserPort 本地实现
 */
@Component
@RequiredArgsConstructor
public class UserPortAdapter implements UserPort {

    private final cn.diaojiahong.myyoutube.user.service.IUserService userService;
    private final UserSummaryCache summaryCache;

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

    private static UserSummary toSummary(User user) {
        UserSummary s = new UserSummary();
        BeanUtil.copyProperties(user, s);
        return s;
    }
}
