package cn.diaojiahong.myyoutube.other.controller;

import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.other.domain.vo.OssStsVO;
import cn.diaojiahong.myyoutube.other.service.IOtherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "其他模块")
@RequiredArgsConstructor
public class OtherController {
    private final IOtherService otherService;

    /**
     * 获取Oss sts 凭证
     * @return
     */
    @GetMapping("/oss/sts")
    @Operation(summary = "获取sts凭证")
    public Result<OssStsVO> getSTSKey() {
        return Result.success(otherService.issueUploadSts());
    }
}
