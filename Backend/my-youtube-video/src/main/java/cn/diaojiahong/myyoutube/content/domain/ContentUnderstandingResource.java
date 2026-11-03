package cn.diaojiahong.myyoutube.content.domain;

import cn.diaojiahong.myyoutube.common.media.ContentArtifacts;

import java.util.Objects;

/** 已完成的摘要、章节和未签名字幕对象键，不包含审核状态、重试次数等可变字段。 */
public record ContentUnderstandingResource(ContentArtifacts.Summary summary, String subtitleKey) {
    public ContentUnderstandingResource {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(summary.chapters(), "chapters");
    }
}
