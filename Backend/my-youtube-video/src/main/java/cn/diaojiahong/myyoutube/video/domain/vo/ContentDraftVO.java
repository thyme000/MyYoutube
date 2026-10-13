package cn.diaojiahong.myyoutube.video.domain.vo;

import cn.diaojiahong.myyoutube.common.media.DynamicMedia;

public record ContentDraftVO(Long draftId, String type, String status, String title, String description,
                             String visibility, Integer allowComment, String coverObjectKey, String objectKey, Long durationMs,
                             DynamicMedia dynamic, String errorMessage, Long mediaVersion, Long preparationId, Integer revision) {}
