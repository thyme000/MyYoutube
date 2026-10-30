package cn.diaojiahong.myyoutube.video.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * video 服务对外契约: 上传草稿的创建/查询, 供 user 服务 presign 链路与 transcode 编排器调用
 */
@FeignClient(name = "my-youtube-video-service", contextId = "mediaDraftPort",
        path = "/internal/video/draft", url = "${my-youtube.video-service.url:}")
public interface MediaDraftPort {

    /** presign 时创建草稿, objectKey 重复(重试场景)返回已有草稿 */
    @PostMapping("/create")
    DraftCreated createDraft(@RequestBody DraftCreate create);

    @GetMapping("/{draftId}")
    DraftInfo getDraft(@PathVariable("draftId") Long draftId);

    /** 按对象键查草稿, webhook 事件只有 key; 不存在返回 null */
    @GetMapping("/by-object-key")
    DraftInfo getByObjectKey(@RequestParam("key") String objectKey);

    @PostMapping("/{draftId}/uploaded")
    boolean uploaded(@PathVariable("draftId") Long draftId);

    @PostMapping("/preparation-reference")
    String preparationReference(@RequestBody PreparationReference reference);

    record PreparationReference(Long preparationId, Long draftId, Long userId, long mediaVersion) {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class DraftCreate {
        private Long userId;
        private String objectKey;
        private String filename;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class DraftCreated {
        private Long draftId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class DraftInfo {
        private Long id;
        private Long userId;
        private String objectKey;
        private String status;
        private Long durationMs;
    }

}
