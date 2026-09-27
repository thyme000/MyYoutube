package cn.diaojiahong.myyoutube.video.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * video 服务对外契约: 用户标签偏好(推荐打分用)
 */
@FeignClient(name = "my-youtube-video-service", contextId = "preferencePort",
        path = "/internal/preference", url = "${my-youtube.video-service.url:}")
public interface PreferencePort {

    /** 用户偏好标签, 按分数降序取 top N */
    @GetMapping("/top")
    List<TagPreferenceSummary> topPreferences(@RequestParam("userId") Long userId,
                                              @RequestParam(value = "limit", defaultValue = "20") int limit);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class TagPreferenceSummary {
        private Long tagId;
        private Double score;
    }
}
