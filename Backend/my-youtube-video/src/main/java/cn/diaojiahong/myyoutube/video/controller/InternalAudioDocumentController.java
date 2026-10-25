package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts.*;
import cn.diaojiahong.myyoutube.video.api.AudioDocumentPort;
import cn.diaojiahong.myyoutube.common.utils.BusinessTime;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/audio-document")
@RequiredArgsConstructor
public class InternalAudioDocumentController implements AudioDocumentPort {
    private final AudioMapper rows;
    @Override public Document document(Long id) {
        var a = rows.selectById(id);
        return a == null ? null : new Document(a.getId(), a.getUploaderId(), a.getTitle(), a.getProcessingStatus().getCode(),
                a.getReviewStatus().getCode(), a.getVisibility(), a.getIsDelete() != 0, a.getIndexVersion(), BusinessTime.toInstant(a.getCreateTime()).toEpochMilli());
    }
    @Override public void acknowledge(Ack ack) {
        if (ack.audioId() == null || ack.version() < 1) throw new IllegalArgumentException("Invalid audio index acknowledgement");
        rows.update(null, new LambdaUpdateWrapper<Audio>().eq(Audio::getId, ack.audioId()).eq(Audio::getIndexVersion, ack.version())
                .set(Audio::getIndexedVersion, ack.version()).set(Audio::getIndexAfter, null).set(Audio::getIndexPendingSince, null));
    }
}
