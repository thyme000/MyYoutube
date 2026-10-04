package cn.diaojiahong.myyoutube.user.api.dto;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * UserSummary 的本地投影工具: 批量拿到摘要后按需取单列, 避免契约上出现
 * 每列一个端点导致同一批 id 反复远程查询
 */
public final class UserSummaries {

    private UserSummaries() {
    }

    /** id -> 昵称(缺失/空昵称记为空串) */
    public static Map<Long, String> toNicknameMap(Collection<UserSummary> users) {
        if (users == null || users.isEmpty()) {
            return Map.of();
        }
        return users.stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserSummary::getId,
                        u -> u.getNickname() != null ? u.getNickname() : "", (a, b) -> a));
    }

    /** id -> 头像url(缺失记为 null, 由调用方兜底) */
    public static Map<Long, String> toAvatarMap(Collection<UserSummary> users) {
        if (users == null || users.isEmpty()) {
            return Map.of();
        }
        return users.stream()
                .filter(u -> u.getId() != null && u.getAvatarUrl() != null)
                .collect(Collectors.toMap(UserSummary::getId, UserSummary::getAvatarUrl, (a, b) -> a));
    }
}
