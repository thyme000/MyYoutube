package cn.diaojiahong.myyoutube.recommend.mapper;

import cn.diaojiahong.myyoutube.recommend.domain.enums.SimAlgorithmEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.ItemSimilarity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ItemSimilarityMapper extends BaseMapper<ItemSimilarity> {

    void batchInsert(@Param("list") List<ItemSimilarity> list);

    void batchUpsert(@Param("list") List<ItemSimilarity> list);

    void deleteByAlgorithm(@Param("algorithm") SimAlgorithmEnum algorithm);
}
