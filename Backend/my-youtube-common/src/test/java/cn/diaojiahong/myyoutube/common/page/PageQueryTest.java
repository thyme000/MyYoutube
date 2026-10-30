package cn.diaojiahong.myyoutube.common.page;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PageQueryTest {
    @Test
    void invalidBoundsCannotReachDatabasePagination() {
        for (int[] bounds : new int[][]{{0, 10}, {-1, 10}, {1, 0}, {1, -1}, {1, 101}}) {
            var query = new PageQuery();
            query.setPageNo(bounds[0]);
            query.setPageSize(bounds[1]);
            assertThrows(BizException.class, query::toPage);
            assertThrows(BizException.class, query::validate);
            assertThrows(BizException.class, () -> PageQuery.validate(bounds[0], bounds[1]));
        }
    }

    @Test
    void validBoundsArePreserved() {
        var query = new PageQuery();
        query.setPageNo(3);
        query.setPageSize(100);
        Page<Object> page = query.toPage();
        assertEquals(3, page.getCurrent());
        assertEquals(100, page.getSize());
    }
}
