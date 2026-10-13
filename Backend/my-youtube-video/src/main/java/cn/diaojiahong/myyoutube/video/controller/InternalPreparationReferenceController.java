package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.video.api.MediaDraftPort.PreparationReference;
import cn.diaojiahong.myyoutube.video.service.PreparationReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/video/draft")
@RequiredArgsConstructor
public class InternalPreparationReferenceController {
    private final PreparationReferenceService references;

    @PostMapping("/preparation-reference")
    public String reference(@RequestBody PreparationReference reference) { return references.reference(reference); }
}
