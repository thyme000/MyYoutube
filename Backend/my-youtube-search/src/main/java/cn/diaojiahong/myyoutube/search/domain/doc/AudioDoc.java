package cn.diaojiahong.myyoutube.search.domain.doc;

import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

@Data
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Document(indexName = AudioDoc.INDEX)
@Setting(replicas = 0, shards = 1)
public class AudioDoc {
    public static final String INDEX = "audio";

    // _id 由写入端指定；源字段保留数值类型，避免默认 ID 映射改变排序。
    @Field(name = "id", type = FieldType.Long)
    private Long id;
    @Field(type = FieldType.Long)
    private Long uploaderId;
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;
    @Field(type = FieldType.Keyword)
    private String processingStatus;
    @Field(type = FieldType.Keyword)
    private String reviewStatus;
    @Field(type = FieldType.Keyword)
    private String visibility;
    @Field(type = FieldType.Boolean)
    private boolean deleted;
    @Field(type = FieldType.Long)
    private long version;
    @Field(type = FieldType.Long)
    private long createdAt;

    public static AudioDoc from(AudioDiscoveryContracts.Document document) {
        return new AudioDoc(document.id(), document.uploaderId(), document.title(), document.processingStatus(),
                document.reviewStatus(), document.visibility(), document.deleted(), document.version(), document.createdAt());
    }
}
