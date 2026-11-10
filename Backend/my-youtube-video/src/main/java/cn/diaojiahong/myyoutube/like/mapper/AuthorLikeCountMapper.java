package cn.diaojiahong.myyoutube.like.mapper;

import cn.diaojiahong.myyoutube.like.domain.po.AuthorLikeCount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

public interface AuthorLikeCountMapper extends BaseMapper<AuthorLikeCount> {

    /** 把一笔作品赞差额记到作者行上，并把 version 加一。计数不为负 */
    int addDelta(@Param("authorId") long authorId, @Param("delta") long delta);
}
