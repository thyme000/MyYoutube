package cn.diaojiahong.myyoutube.video.domain.dto;

import lombok.Data;

import java.util.Set;

@Data
public class UserShortsWatchRecords {
    // 用户id
    private Long userId;
    // 短视频id集合
    private Set<Long> shortIds;
}
