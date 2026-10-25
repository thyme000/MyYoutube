package cn.diaojiahong.myyoutube.recommend.domain.doc;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;
import org.springframework.data.elasticsearch.core.suggest.Completion;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Document(indexName = "content")
// 单节点
@Setting(replicas = 0, shards = 1)
public class ContentDoc {

    @Id
    private Long id;

    @Field(type = FieldType.Long)
    private Long indexVersion;

    @Field(type = FieldType.Boolean)
    private Boolean deleted;

    /** 内容类型: video/dynamic */
    @Field(type = FieldType.Keyword)
    private String type;

    /** 封面 objectKey: 视频用封面, 动态用首帧 */
    @Field(type = FieldType.Keyword)
    private String coverUrl;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String description;

    @Field(type = FieldType.Text, index = false)
    private String summary;

    @Field(type = FieldType.Keyword)
    private String category;

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

    @Field(type = FieldType.Keyword)
    private String visibility;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private List<String> tagNames;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String authorName;

    @CompletionField(maxInputLength = 100, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private Completion suggest;

    /** 标题+简介+摘要语义向量; dims 与 llm-svc embedding 配置绑定, 换模型需全量重算 */
    @Field(type = FieldType.Dense_Vector, dims = 4096)
    private List<Float> vector;

    /** 向量空间版本, 由 llm-svc 盖戳; 查询端 kNN 只召回与当前配置同版本的文档 */
    @Field(type = FieldType.Keyword)
    private String vectorVersion;
}
