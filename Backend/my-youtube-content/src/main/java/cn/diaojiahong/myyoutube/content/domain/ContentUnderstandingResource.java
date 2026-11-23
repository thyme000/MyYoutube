package cn.diaojiahong.myyoutube.content.domain;

import cn.diaojiahong.myyoutube.common.media.ContentArtifacts;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.content.domain.po.ContentUnderstanding;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 已完成的摘要、章节和未签名字幕对象键，不包含审核状态、重试次数等可变字段。 */
public record ContentUnderstandingResource(ContentArtifacts.Summary summary, String subtitleKey) {
    public ContentUnderstandingResource {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(summary.chapters(), "chapters");
    }

    /** 审核时只复制展示字段，章节解析留在后台预热阶段。 */
    public record Snapshot(boolean received, String summary, String chaptersJson, String subtitleKey) {
        public static Snapshot from(ContentUnderstanding row) {
            return new Snapshot(Boolean.TRUE.equals(row.getReceived()), row.getSummary(),
                    row.getChaptersJson(), row.getSubtitleKey());
        }

        public ContentUnderstandingResource toResource() {
            if (!received) {
                return null;
            }

            List<ContentArtifacts.Chapter> chapters = chaptersJson == null ? List.of()
                    : Arrays.asList(JsonUtil.fromJson(chaptersJson, ContentArtifacts.Chapter[].class));
            return new ContentUnderstandingResource(new ContentArtifacts.Summary(summary, chapters), subtitleKey);
        }
    }
}
