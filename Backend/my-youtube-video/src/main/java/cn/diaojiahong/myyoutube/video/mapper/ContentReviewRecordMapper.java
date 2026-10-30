package cn.diaojiahong.myyoutube.video.mapper;
import cn.diaojiahong.myyoutube.video.domain.po.ContentReviewRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

@Mapper
public interface ContentReviewRecordMapper extends BaseMapper<ContentReviewRecord> {
    // 只认最新审核记录；同一审核时间以 ID 确定顺序，旧的音频封禁不能覆盖后续结论。
    default Set<Long> audioBannedContentIds(Collection<Long> contentIds) {
        if (contentIds.isEmpty()) return Set.of();
        return selectList(new LambdaQueryWrapper<ContentReviewRecord>().select(ContentReviewRecord::getContentId)
                .in(ContentReviewRecord::getContentId, contentIds).eq(ContentReviewRecord::getSource, "AUDIO")
                .eq(ContentReviewRecord::getDecision, "BAN")
                .notExists("""
                        SELECT 1 FROM content_review_record newer
                        WHERE newer.content_id = content_review_record.content_id
                          AND (newer.create_time > content_review_record.create_time
                            OR (newer.create_time = content_review_record.create_time AND newer.id > content_review_record.id))
                        """)).stream()
                .map(ContentReviewRecord::getContentId).collect(Collectors.toSet());
    }
}
