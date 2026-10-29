package cn.diaojiahong.myyoutube.content.mapper;

import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentVisibilityEnum;
import org.apache.ibatis.annotations.Param;

public interface ContentStatisticsMapper {
    long historicalAuthorCount(@Param("audioId") long audioId);

    long displayCount(@Param("authorId") long authorId, @Param("type") ContentTypeEnum type,
            @Param("visibility") ContentVisibilityEnum visibility);

    int changePublished(@Param("authorId") long authorId, @Param("type") ContentTypeEnum type,
            @Param("visibility") ContentVisibilityEnum visibility, @Param("delta") int delta);

    int changeReferences(@Param("audioId") long audioId, @Param("authorId") long authorId,
            @Param("type") ContentTypeEnum type, @Param("visibility") ContentVisibilityEnum visibility,
            @Param("delta") int delta);
}
