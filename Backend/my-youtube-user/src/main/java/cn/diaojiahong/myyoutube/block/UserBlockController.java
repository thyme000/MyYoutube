package cn.diaojiahong.myyoutube.block;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/block")
@RequiredArgsConstructor
public class UserBlockController {
    private final UserBlockService blocks;

    @PutMapping("/{userId}")
    public Result<Void> block(@PathVariable Long userId) {
        blocks.block(viewerId(), userId);
        return Result.success();
    }

    @DeleteMapping("/{userId}")
    public Result<Void> unblock(@PathVariable Long userId) {
        blocks.unblock(viewerId(), userId);
        return Result.success();
    }

    private static Long viewerId() {
        String userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return Long.valueOf(userId);
    }
}
