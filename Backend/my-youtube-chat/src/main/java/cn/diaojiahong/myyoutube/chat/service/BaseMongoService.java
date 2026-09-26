package cn.diaojiahong.myyoutube.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public abstract class BaseMongoService<T> {

    protected final MongoTemplate mongoTemplate;
    private final Class<T> entityClass;

    public T save(T entity) {
        touchTime(entity, true);
        return mongoTemplate.save(entity);
    }

    public Optional<T> getById(String id) {
        return Optional.ofNullable(mongoTemplate.findById(id, entityClass));
    }

    public boolean removeById(String id) {
        Query q = Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id));
        return mongoTemplate.remove(q, entityClass).getDeletedCount() > 0;
    }

    public List<T> list(Query query) {
        return mongoTemplate.find(query, entityClass);
    }

    public Page<T> page(Query query, Pageable pageable) {
        long total = mongoTemplate.count(query, entityClass);
        query.with(pageable);
        List<T> records = mongoTemplate.find(query, entityClass);
        return new org.springframework.data.domain.PageImpl<>(records, pageable, total);
    }

    @SuppressWarnings("unchecked")
    private void touchTime(T entity, boolean isCreate) {
        if (entity instanceof cn.diaojiahong.myyoutube.chat.mongo.base.BaseMongoEntity base) {
            LocalDateTime now = LocalDateTime.now();
            if (isCreate && base.getCreatedAt() == null) {
                base.setCreatedAt(now);
            }
            base.setUpdatedAt(now);
        }
    }
}
