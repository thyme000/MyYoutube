package cn.diaojiahong.myyoutube.video.domain.vo;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AudioPage {
    private List<AudioVO> list;
    private String nextCursor;
    /** 与 nextCursor 同时成立：为真时 nextCursor 有值。 */
    private boolean hasMore;
}
