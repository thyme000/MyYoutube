package cn.diaojiahong.myyoutube.content.controller;

import cn.diaojiahong.myyoutube.content.api.MediaDraftPort.PreparationReference;
import cn.diaojiahong.myyoutube.content.service.PreparationReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/media-drafts")
@RequiredArgsConstructor
public class InternalPreparationReferenceController {
    private final PreparationReferenceService references;

    @PostMapping("/preparation-reference")
    public String reference(@RequestBody PreparationReference reference) {
        return references.reference(reference);
    }
}
