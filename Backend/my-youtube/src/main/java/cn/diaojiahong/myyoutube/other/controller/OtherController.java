package cn.diaojiahong.myyoutube.other.controller;

import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.other.domain.vo.OssPresignVO;
import cn.diaojiahong.myyoutube.other.service.IOtherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "其他模块")
@RequiredArgsConstructor
public class OtherController {
    private final IOtherService otherService;

    /**
     * 获取前端直传预签名 PUT URL
     */
    @GetMapping("/oss/presign")
    @Operation(summary = "获取上传预签名URL")
    public Result<OssPresignVO> presignUpload(@RequestParam String bizType,
                                              @RequestParam(required = false) String filename) {
        return Result.success(otherService.presignUpload(bizType, filename));
    }
}
