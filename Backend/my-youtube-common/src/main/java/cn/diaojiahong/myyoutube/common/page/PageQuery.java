package cn.diaojiahong.myyoutube.common.page;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
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
        validate();
        return new Page<>(pageNo, pageSize);
    }

    public void validate() { validate(pageNo, pageSize); }

    public static void validate(int pageNo, int pageSize) {
        if (pageNo < 1 || pageSize < 1 || pageSize > 100)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "分页参数无效，页码须为正数，每页条数为 1~100");
    }
}
