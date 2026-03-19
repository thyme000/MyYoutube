package cn.diaojiahong.myyoutube.common.page;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "分页查询参数")
public class PageQuery {

    @Schema(description = "页码，从 1 开始", defaultValue = "1", example = "1")
    private int pageNo = 1;

    @Schema(description = "每页条数", defaultValue = "10", example = "10")
    private int pageSize = 10;

    /**
     * 转换为 MyBatis Plus 的 Page 对象
     */
    public <T> Page<T> toPage() {
        return new Page<>(pageNo, pageSize);
    }
}
