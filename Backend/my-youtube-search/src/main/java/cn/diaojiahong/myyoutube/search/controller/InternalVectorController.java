package cn.diaojiahong.myyoutube.search.controller;

import cn.diaojiahong.myyoutube.search.vector.ContentVectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * search 服务向量内部接口: 存量重建触发/进度查询, 运维或脚本调用, 不经网关暴露
 */
@RestController
@RequestMapping("/internal/vector")
@RequiredArgsConstructor
public class InternalVectorController {

    private final ContentVectorService contentVectorService;

    /** 触发存量重建: 已在跑返回 false, 中断后重新触发自动从游标续跑 */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() {
        boolean started = contentVectorService.startRebuild();
        return Map.of("started", started, "running", contentVectorService.isRebuildRunning());
    }

    @GetMapping("/rebuild/status")
    public Map<String, Object> rebuildStatus() {
        Map<String, Object> status = new java.util.HashMap<>();
        status.put("running", contentVectorService.isRebuildRunning());
        status.put("cursor", contentVectorService.rebuildCursor());
        return status;
    }
}
