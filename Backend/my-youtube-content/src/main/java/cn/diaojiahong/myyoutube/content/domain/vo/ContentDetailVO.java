package cn.diaojiahong.myyoutube.content.domain.vo;

import java.util.List;
import cn.diaojiahong.myyoutube.common.media.ContentArtifacts.Chapter;
import cn.diaojiahong.myyoutube.content.api.dto.ContentInfoVO;
import cn.diaojiahong.myyoutube.content.api.dto.ContentMediaVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ContentDetailVO extends ContentInfoVO {
    private Boolean isLike;
    private Boolean isFollow;
    private ContentMediaVO media;
    private String summary;
    private List<Chapter> chapters;
    private String subtitleUrl;
}
