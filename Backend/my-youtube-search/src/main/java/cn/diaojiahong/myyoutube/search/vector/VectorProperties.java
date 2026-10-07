package cn.diaojiahong.myyoutube.search.vector;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.search.vector")
public class VectorProperties {

    /** 总开关, 关闭即回退纯关键词搜索 */
    private boolean enabled = true;
    /** RRF 平滑常数 */
    private int rrfK = 60;
    /** 双路各取的召回深度 */
    private int recallSize = 50;
}
