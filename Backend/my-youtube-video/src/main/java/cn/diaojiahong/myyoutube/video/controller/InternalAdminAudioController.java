package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.page.*;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.AudioAdminPort;
import cn.diaojiahong.myyoutube.video.domain.enums.*;
import cn.diaojiahong.myyoutube.video.domain.po.*;
import cn.diaojiahong.myyoutube.video.mapper.*;
import cn.diaojiahong.myyoutube.video.service.AudioReviewService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/internal/admin/audios")
@RequiredArgsConstructor
public class InternalAdminAudioController implements AudioAdminPort {
    private final AudioMapper rows;
    private final AudioReviewRecordMapper records;
    private final AudioReviewService reviews;
    private final S3StorageUtil storage;
    private final ContentArtifactStore artifacts;
    private final UserPort users;

    @Override
    public PageResult<Item> page(PageQuery query) {
        Page<Audio> page = rows.selectPage(query.toPage(), new LambdaQueryWrapper<Audio>()
                .select(Audio::getId, Audio::getTitle, Audio::getProcessingStatus, Audio::getReviewStatus,
                        Audio::getAnalysisStatus, Audio::getHistoricalAuthorCount)
                .eq(Audio::getIsDelete, 0)
                .orderByDesc(Audio::getCreateTime).orderByDesc(Audio::getId));

        return PageResult.of(page.getTotal(), page.getPages(), page.getRecords().stream().map(this::item).toList());
    }

    @Override public PageResult<Item> waiting(PageQuery query) {
        var page = rows.selectPage(query.toPage(), new LambdaQueryWrapper<Audio>().eq(Audio::getIsDelete, 0)
                .eq(Audio::getReviewStatus, AudioReviewStatus.PENDING).eq(Audio::getProcessingStatus, AudioProcessingStatus.READY)
                .in(Audio::getAnalysisStatus, AudioAnalysisStatus.MANUAL, AudioAnalysisStatus.FAILED)
                .orderByAsc(Audio::getCreateTime).orderByAsc(Audio::getId));
        return PageResult.of(page.getTotal(), page.getPages(), page.getRecords().stream().map(this::item).toList());
    }
    @Override public Detail detail(Long id) {
        var audio = rows.selectById(id);
        if (audio == null) throw new BizException(ErrorCode.NOT_FOUND, "音频不存在");
        String preview = audio.getObjectKey() == null ? null : storage.signGetUrl(audio.getObjectKey(), 600);
        String cover = audio.getCoverObjectKey();
        if (cover == null) {
            var summaries = users.listByIds(List.of(audio.getUploaderId()));
            if (summaries != null && !summaries.isEmpty()) cover = summaries.get(0).getAvatarUrl();
        }
        ContentArtifacts.Transcript transcript = null;
        if (audio.getTranscriptKey() != null) {
            transcript = artifacts.read(audio.getTranscriptKey(), ContentArtifacts.Transcript.class);
            AudioTranscript.validate(transcript, audio.getDurationMs());
        }
        var history = records.selectList(new LambdaQueryWrapper<AudioReviewRecord>().eq(AudioReviewRecord::getAudioId, id)
                .orderByAsc(AudioReviewRecord::getCreateTime).orderByAsc(AudioReviewRecord::getId));
        return new Detail(item(audio), preview, cover == null || cover.isBlank() ? null : storage.signGetUrl(cover, 600), transcript,
                audio.getErrorMessage(), history.stream().map(r -> new Audit(r.getSource(), r.getDecision(), r.getReason(), r.getPayloadJson(),
                r.getAttempt(), r.getActorId() == null ? null : r.getActorId().toString(), r.getCreateTime())).toList());
    }
    @Override public boolean decide(Long id, Decision decision) {
        return reviews.decide(id, AudioReviewDecision.parse(decision.decision()), decision.reason(), decision.actorId());
    }
    private Item item(Audio a) {
        return new Item(a.getId().toString(), a.getTitle(), a.getProcessingStatus().getCode(), a.getReviewStatus().getCode(),
                a.getAnalysisStatus() == null ? null : a.getAnalysisStatus().getCode(), a.getHistoricalAuthorCount());
    }
}
