package cn.diaojiahong.myyoutube.video.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * video 服务对外契约: 关注关系
 */
@FeignClient(name = "my-youtube-video-service", contextId = "followPort",
        path = "/internal/follow", url = "${my-youtube.video-service.url:}")
public interface FollowPort {

    /** 用户的关注列表 id */
    @GetMapping("/follow-ids")
    List<Long> getFollowIds(@RequestParam("userId") Long userId);

    /** userId 与 memberIds 中每个人是否互相关注 */
    @PostMapping("/interrelated")
    boolean interrelated(@RequestBody InterrelatedQuery query);

    /** 粉丝数权威值: COUNT(user_follow WHERE followee_id=? AND is_deleted=0), 供 user 服务对账覆盖 */
    @GetMapping("/fans-count")
    Long fansCount(@RequestParam("userId") Long userId);

    /** 关注数权威值: COUNT(user_follow WHERE follower_id=? AND is_deleted=0) */
    @GetMapping("/attention-count")
    Long attentionCount(@RequestParam("userId") Long userId);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class InterrelatedQuery {
        private Long userId;
        private List<Long> memberIds;
    }
}
