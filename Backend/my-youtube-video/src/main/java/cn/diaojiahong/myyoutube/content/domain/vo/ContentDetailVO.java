package cn.diaojiahong.myyoutube.content.domain.vo;

import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ContentDetailVO extends cn.diaojiahong.myyoutube.video.api.dto.ContentInfoVO {
    private Boolean isLike;
    private Boolean isFollow;
    private cn.diaojiahong.myyoutube.video.api.dto.ContentMediaVO media;
    private String summary;
    private List<cn.diaojiahong.myyoutube.common.media.ContentArtifacts.Chapter> chapters;
    private String subtitleUrl;
}
