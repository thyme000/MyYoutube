package cn.diaojiahong.myyoutube.recommend.domain.doc;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Document(indexName = "video")
// 单节点
@Setting(replicas = 0, shards = 1)
public class VideoDoc {

    @Id
    private Long id;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String description;

    @Field(type = FieldType.Keyword)
    private String category;

    // "short" / "long"
    @Field(type = FieldType.Keyword)
    private String videoType;

    @Field(type = FieldType.Keyword)
    private String status;

    // 存这个视频关联的所有标签id
    @Field(type = FieldType.Long)
    private List<Long> tagIds;

    @Field(type = FieldType.Long)
    private Long authorId;

    @Field(type = FieldType.Long)
    private Long playCount;

    @Field(type = FieldType.Long)
    private Long likeCount;

    @Field(type = FieldType.Date, format = {}, pattern = "yyyy-MM-dd HH:mm:ss || epoch_millis")
    private LocalDateTime createTime;
}
