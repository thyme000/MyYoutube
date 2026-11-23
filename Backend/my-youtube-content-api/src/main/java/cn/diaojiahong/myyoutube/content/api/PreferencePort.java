package cn.diaojiahong.myyoutube.content.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 内容服务对外契约: 用户推荐偏好
 */
@FeignClient(name = "my-youtube-content-service", contextId = "preferencePort",
        path = "/internal/preference", url = "${my-youtube.content-service.url:}")
public interface PreferencePort {

    /** 用户正分偏好标签，按分数降序取 top N。 */
    @GetMapping("/top")
    List<TagPreferenceSummary> topPreferences(@RequestParam("userId") Long userId,
                                              @RequestParam(value = "limit", defaultValue = "20") int limit);

    @GetMapping("/disliked-ids")
    List<Long> activeDislikedIds(@RequestParam("userId") Long userId);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class TagPreferenceSummary {
        private Long tagId;
        private Double score;
    }
}
