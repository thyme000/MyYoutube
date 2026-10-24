package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.media.PipelineStatus;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import java.util.List;
import java.util.Set;

public final class PipelineState {
    private PipelineState() {}
    public static PipelineStatus derive(List<MediaTask> nodes) {
        if (nodes.stream().anyMatch(t -> t.getStatus() == null))
            throw new IllegalStateException("Missing Pipeline node state");
        if (nodes.isEmpty()) throw new IllegalStateException("Pipeline has no nodes");
        if (nodes.stream().allMatch(t -> t.getStatus() == MediaTaskStatus.SUCCESS))
            return PipelineStatus.COMPLETED;
        if (nodes.stream().anyMatch(t -> Set.of(MediaTaskStatus.READY, MediaTaskStatus.RUNNING, MediaTaskStatus.RETRY_WAIT)
                .contains(t.getStatus()))) return PipelineStatus.PROCESSING;
        if (nodes.stream().anyMatch(t -> t.getStatus() == MediaTaskStatus.PENDING))
            throw new IllegalStateException("Pipeline dependency graph cannot advance");
        return PipelineStatus.WAITING_ARTIFACTS;
    }
}
