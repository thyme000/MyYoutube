package cn.diaojiahong.myyoutube.search.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 视频可见性同步参数; rebuildSuggest 时需要 title/description/tags
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VisibilitySync {
    private String title;
    private String description;
    private String tags;
    private String visibility;
    private boolean rebuildSuggest;
}
