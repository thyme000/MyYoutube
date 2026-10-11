package cn.diaojiahong.myyoutube.video.controller;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.*;
import cn.diaojiahong.myyoutube.video.api.ContentPipelinePort;
import cn.diaojiahong.myyoutube.video.service.ReviewDecisionService;
import cn.diaojiahong.myyoutube.recommend.auto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/internal/content-pipeline")
@RequiredArgsConstructor
public class InternalContentPipelineController implements ContentPipelinePort {
    private final TaggingService tagging;
    private final ReviewDecisionService decisions;
    @Override public String generateTags(TagRequest request) {
        String text = java.util.stream.Stream.of(request.title(), request.description(), request.summary())
                .filter(v -> v != null && !v.isBlank()).collect(java.util.stream.Collectors.joining("\n"));
        return tagging.generate(TaggingInput.ofText(text));
    }
    @Override public ReviewSpec reviewSpec(Long pipelineId, int attempt) { return decisions.begin(pipelineId, attempt); }
}
