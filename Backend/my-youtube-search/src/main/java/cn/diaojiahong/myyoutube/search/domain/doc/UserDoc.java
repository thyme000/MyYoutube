package cn.diaojiahong.myyoutube.search.domain.doc;


import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;
import org.springframework.data.elasticsearch.core.suggest.Completion;

import java.time.LocalDateTime;

@Data
@Document(indexName = "user")
@Setting(replicas = 0, shards = 1)
public class UserDoc {

    @Id
    private Long id;

    @Field(type = FieldType.Keyword)
    private String idKeyword;

    @Field(type = FieldType.Keyword)
    private String username;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String nickname;

    @Field(type = FieldType.Long)
    private Long fans;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String introduction;
    
    // 获赞数   
    @Field(type = FieldType.Long)
    private Long likeCount;

    @Field(type = FieldType.Date, format = {}, pattern = "yyyy-MM-dd HH:mm:ss || epoch_millis")
    private LocalDateTime createTime;

    @CompletionField(maxInputLength = 100, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private Completion suggest;
}
