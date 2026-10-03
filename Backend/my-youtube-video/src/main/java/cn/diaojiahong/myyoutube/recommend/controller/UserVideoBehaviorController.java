package cn.diaojiahong.myyoutube.recommend.controller;


import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.recommend.domain.dto.BehaviorDTO;
import cn.diaojiahong.myyoutube.recommend.service.IUserVideoBehaviorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 用户-内容行为流水 前端控制器
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
@RestController
@RequestMapping("/behavior")
@RequiredArgsConstructor
@Tag(name = "用户行为模块")
public class UserVideoBehaviorController {

    private final IUserVideoBehaviorService userVideoBehaviorService;

    /**
     * 用户观看内容的行为记录(视频/动态共用)
     * @param behaviorDTO
     * @return
     */
    @PostMapping("/content")
    @Operation(summary = "用户观看内容行为")
    public Result<Void> report(@RequestBody BehaviorDTO behaviorDTO) {
        return userVideoBehaviorService.report(behaviorDTO);
    }
}
