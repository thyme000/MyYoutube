package cn.diaojiahong.myyoutube.user.api.dto;

/**
 * 把作者获赞的绝对值抄到 user.like_count。version 不大于已收下的版本时不覆盖计数。
 */
public record ReceivedLikeUpdate(long userId, long likeCount, long version) {
}
