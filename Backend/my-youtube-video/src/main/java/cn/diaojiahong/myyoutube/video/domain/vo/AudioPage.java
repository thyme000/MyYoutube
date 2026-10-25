package cn.diaojiahong.myyoutube.video.domain.vo;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AudioPage {
    private List<AudioVO> list;
    private boolean hasMore;
}
