package cn.diaojiahong.myyoutube.search.controller;

import cn.diaojiahong.myyoutube.search.domain.doc.UserDocRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * search 服务内部接口: 用户 ES 文档计数调整
 */
@RestController
@RequestMapping("/internal/user-doc")
@RequiredArgsConstructor
public class InternalUserDocController {

    private final UserDocRepository userDocRepository;

    @PostMapping("/video-count/adjust")
    public void adjustVideoCount(@RequestParam("userId") Long userId, @RequestParam("delta") Integer delta) {
        userDocRepository.findById(userId).ifPresent(doc -> {
            long target = doc.getVideoCount() != null ? doc.getVideoCount() + delta : delta;
            doc.setVideoCount(Math.max(0L, target));
            userDocRepository.save(doc);
        });
    }

    @PostMapping("/like-count/adjust")
    public void adjustLikeCount(@RequestParam("userId") Long userId, @RequestParam("delta") Long delta) {
        userDocRepository.findById(userId).ifPresent(doc -> {
            doc.setLikeCount((doc.getLikeCount() != null ? doc.getLikeCount() : 0L) + delta);
            userDocRepository.save(doc);
        });
    }
}
