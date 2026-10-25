package cn.diaojiahong.myyoutube.admin.controller;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.*;
import cn.diaojiahong.myyoutube.common.page.*;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.video.api.AudioAdminPort;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/audios")
@RequiredArgsConstructor
public class AdminAudioController {
    private final AudioAdminPort audios;
    public record Decision(String decision, String reason) {}
    private Long requireAdmin() {
        if (!"1".equals(UserContext.getUserId())) throw new BizException(ErrorCode.FORBIDDEN, "需要管理员权限");
        return 1L;
    }
    @GetMapping("/waiting") public Result<PageResult<AudioAdminPort.Item>> waiting(@ModelAttribute PageQuery query) {
        requireAdmin(); query.validate(); return Result.success(audios.waiting(query));
    }
    @GetMapping("/{id}") public Result<AudioAdminPort.Detail> detail(@PathVariable Long id) {
        requireAdmin(); return Result.success(audios.detail(id));
    }
    @PostMapping("/{id}/decision") public Result<Boolean> decide(@PathVariable Long id, @RequestBody Decision decision) {
        Long actor = requireAdmin();
        return Result.success(audios.decide(id, new AudioAdminPort.Decision(decision.decision(), decision.reason(), actor)));
    }
}
