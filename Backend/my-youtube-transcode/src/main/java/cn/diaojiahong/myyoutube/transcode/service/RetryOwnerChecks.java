package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import cn.diaojiahong.myyoutube.transcode.mapper.*;
import cn.diaojiahong.myyoutube.transcode.preparation.PreparationRegistry;
import cn.diaojiahong.myyoutube.video.api.MediaRetryOwnerPort;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.Input;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RetryOwnerChecks {
    private final MediaRetryOwnerPort owners;
    private final PipelineMapper pipelines;
    private final MediaPreparationMapper preparations;
    private final ObjectProvider<PreparationRegistry> registry;

    public String reason(MediaTask row) {
        if (row == null) return "TASK_MISSING";
        Long ownerId=row.getOwnerId(), preparationId=null, version=null;
        if ("PREPARE".equals(row.getTaskType())) {
            var preparation=preparations.selectById(ownerId);
            if (preparation==null || !"ACTIVE".equals(preparation.getLifecycle())) return "OWNER_RETIRED";
            if (!preparation.getParameterVersion().equals(registry.getObject().parameterVersion())) return "PREPARATION_PARAMETERS_CHANGED";
            ownerId=preparation.getDraftId(); preparationId=preparation.getId(); version=preparation.getMediaVersion();
        } else if (row.getPipelineId()!=null) {
            var pipeline=pipelines.selectById(row.getPipelineId());
            if (pipeline==null) return "PIPELINE_MISSING";
            var input=JsonUtil.fromJson(pipeline.getInputJson(),Input.class);
            var prepared=input.media().preparation();
            if (prepared!=null) {
                var record=preparations.selectById(prepared.preparationId());
                if (record==null || !"ACTIVE".equals(record.getLifecycle())) return "OWNER_RETIRED";
            }
        }
        var result=owners.check(new MediaRetryOwnerPort.Owner(row.getPhase(),ownerId,row.getPipelineId(),preparationId,version));
        if (result==null) throw new IllegalStateException("Missing retry owner response");
        return result.allowed() ? null : result.reason()==null ? "OWNER_STATE_CHANGED" : result.reason();
    }
}
