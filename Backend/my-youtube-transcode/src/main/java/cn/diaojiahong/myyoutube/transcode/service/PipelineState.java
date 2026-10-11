package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import java.util.List;
import java.util.Set;

public final class PipelineState {
    private PipelineState() {}
    public static String derive(List<MediaTask> nodes) {
        if (nodes.stream().anyMatch(t -> !Set.of("PENDING","READY","RUNNING","RETRY_WAIT","SUCCESS","DEAD","BLOCKED").contains(t.getStatus())))
            throw new IllegalStateException("Unknown Pipeline node state");
        if (nodes.isEmpty()) throw new IllegalStateException("Pipeline has no nodes");
        if (nodes.stream().allMatch(t -> "SUCCESS".equals(t.getStatus()))) return "COMPLETED";
        if (nodes.stream().anyMatch(t -> Set.of("READY", "RUNNING", "RETRY_WAIT").contains(t.getStatus()))) return "PROCESSING";
        if (nodes.stream().anyMatch(t -> "PENDING".equals(t.getStatus())))
            throw new IllegalStateException("Pipeline dependency graph cannot advance");
        return "WAITING_ARTIFACTS";
    }
}
