package cn.diaojiahong.myyoutube.admin.controller;

import cn.diaojiahong.myyoutube.admin.security.AdminAuthorization;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.video.api.AudioAdminPort;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/audios")
@RequiredArgsConstructor
public class AdminAudioController {
    private final AudioAdminPort audios;
    private final AdminAuthorization authorization;

    public record Decision(String decision, String reason) {
    }

    @GetMapping
    public Result<PageResult<AudioAdminPort.Item>> page(@ModelAttribute PageQuery query) {
        authorization.requireAdmin();
        query.validate();

        return Result.success(audios.page(query));
    }

    @GetMapping("/waiting")
    public Result<PageResult<AudioAdminPort.Item>> waiting(@ModelAttribute PageQuery query) {
        authorization.requireAdmin();
        query.validate();

        return Result.success(audios.waiting(query));
    }

    @GetMapping("/{id}")
    public Result<AudioAdminPort.Detail> detail(@PathVariable Long id) {
        authorization.requireAdmin();

        return Result.success(audios.detail(id));
    }

    @PostMapping("/{id}/decision")
    public Result<Boolean> decide(@PathVariable Long id, @RequestBody Decision decision) {
        Long actor = authorization.requireAdmin();
        return Result.success(audios.decide(
                id,
                new AudioAdminPort.Decision(decision.decision(), decision.reason(), actor)));
    }
}
