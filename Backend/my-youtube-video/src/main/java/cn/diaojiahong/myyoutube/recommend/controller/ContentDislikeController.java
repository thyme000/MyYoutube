package cn.diaojiahong.myyoutube.recommend.controller;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.recommend.service.ContentDislikeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ContentDislikeController {
    private final ContentDislikeService dislikes;

    @PostMapping("/like/dislike/{contentId}")
    public Result<Status> mark(@PathVariable long contentId, @RequestBody(required = false) Feedback body) {
        Long watchDuration = body == null ? null : body.watchDuration();
        if (contentId <= 0 || (watchDuration != null && watchDuration < 0)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "内容负反馈无效");
        }
        long expiresAt = dislikes.mark(UserContext.requireUserId(), contentId, watchDuration);
        return Result.success(new Status(expiresAt));
    }

    @GetMapping("/like/dislike/{contentId}")
    public ResponseEntity<Result<Status>> status(@PathVariable long contentId) {
        Long expiresAt = dislikes.activeExpiry(UserContext.requireUserId(), contentId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(new Status(expiresAt)));
    }

    @GetMapping("/internal/preference/disliked-ids")
    public List<Long> activeContentIds(@RequestParam long userId) {
        return dislikes.activeContentIds(userId);
    }

    public record Feedback(Long watchDuration) {
    }

    public record Status(Long expiresAt) {
    }
}
