package cn.diaojiahong.myyoutube.transcode.controller;

import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort;
import cn.diaojiahong.myyoutube.transcode.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/internal/media-retries")
@RequiredArgsConstructor
public class InternalMediaRetryController implements MediaRetryPort {
    private final MediaRetryService service;
    private final RetryBatchStore batches;
    @Override public Page query(Query query) { return service.query(query); }
    @Override public Admission create(Request request, Long actorId) { return service.create(request,actorId); }
    @Override public BatchPage history(Long cursor, Integer size) { return batches.history(cursor,size); }
    @Override public Batch current() { return batches.current(); }
    @Override public Batch batch(Long batchId) { return batches.batch(batchId); }
    @Override public List<Affected> affected(Long batchId) { return batches.affected(batchId); }
    @Override public List<Item> items(Long batchId) { return batches.items(batchId); }
}
