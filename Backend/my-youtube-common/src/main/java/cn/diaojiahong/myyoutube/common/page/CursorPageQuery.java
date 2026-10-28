package cn.diaojiahong.myyoutube.common.page;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "游标分页查询参数")
public class CursorPageQuery {
    @Schema(description = "每批请求数量", defaultValue = "10", example = "10")
    private int pageSize = 10;

    @Schema(description = "后端返回的续查游标，首次查询不传")
    private String cursor;

    public void validate() {
        PageQuery.validatePageSize(pageSize);
    }
}
