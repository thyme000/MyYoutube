package cn.diaojiahong.myyoutube.common.page;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "游标分页查询参数。每页条数由服务端决定，调用方只回传游标")
public class CursorPageQuery {
    /** 面向用户的游标列表每页条数。 */
    public static final int PAGE_SIZE = 10;

    @Schema(description = "后端返回的续查游标，首次查询不传")
    private String cursor;
}
