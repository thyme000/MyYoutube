package cn.diaojiahong.myyoutube.video.api;

import cn.diaojiahong.myyoutube.common.media.ContentArtifacts.Transcript;
import cn.diaojiahong.myyoutube.common.page.*;
import cn.diaojiahong.myyoutube.common.feign.PropagateBusinessErrors;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.time.LocalDateTime;

@PropagateBusinessErrors({ErrorCode.INVALID_ARGUMENT, ErrorCode.NOT_FOUND, ErrorCode.STATE_CONFLICT})
@FeignClient(name = "my-youtube-video-service", contextId = "audioAdminPort", path = "/internal/admin/audios", url = "${my-youtube.video-service.url:}")
public interface AudioAdminPort {
    @PostMapping("/waiting") PageResult<Item> waiting(@RequestBody PageQuery query);
    @GetMapping("/{id}") Detail detail(@PathVariable("id") Long id);
    @PostMapping("/{id}/decision") boolean decide(@PathVariable("id") Long id, @RequestBody Decision decision);
    record Item(String id, String title, String processingStatus, String reviewStatus, String analysisStatus) {}
    record Audit(String source, String decision, String reason, String payloadJson, Integer attempt, String actorId, LocalDateTime createTime) {}
    record Detail(Item audio, String previewUrl, String coverUrl, Transcript transcript, String technicalError, List<Audit> records) {}
    record Decision(String decision, String reason, Long actorId) {}
}
