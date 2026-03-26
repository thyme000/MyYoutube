package cn.diaojiahong.myyoutube.recommend.domain.doc;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface VideoDocRepository extends ElasticsearchRepository<VideoDoc, Long> {
}
