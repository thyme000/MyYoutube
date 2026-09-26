package cn.diaojiahong.myyoutube.common.page;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "分页响应结果")
public class PageResult<T> {

    @Schema(description = "总条数")
    private long total;

    @Schema(description = "总页数")
    private long pages;

    @Schema(description = "当前页数据列表")
    private List<T> list;

    private PageResult(long total, long pages, List<T> list) {
        this.total = total;
        this.pages = pages;
        this.list = list;
    }

    /**
     * 从 MyBatis Plus 的 Page 对象直接构建
     */
    public static <T> PageResult<T> of(Page<T> page) {
        return new PageResult<>(page.getTotal(), page.getPages(), page.getRecords());
    }

    /**
     * 手动传入数据构建（适合自定义查询场景）
     */
    public static <T> PageResult<T> of(long total, long pages, List<T> list) {
        return new PageResult<>(total, pages, list);
    }
}
