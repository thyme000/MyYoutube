package cn.diaojiahong.myyoutube.common.monitoring;

/** 自定义指标名称统一在此维护；名称保持稳定，便于告警和看板引用。 */
public final class MetricNames {
    private MetricNames() {
    }

    public static final String ASYNC_REJECTED = "myyoutube.async.rejected";
    public static final String ASYNC_TIMEOUTS = "myyoutube.async.timeouts";

    public static final String AUDIO_CASCADE_NOTIFICATION_FAILED = "audio.cascade.notification.failed";
    public static final String AUDIO_CASCADE_RECOVERY_FAILED = "audio.cascade.recovery.failed";
    public static final String AUDIO_INDEX_DELIVERY_FAILURES = "myyoutube.audio.index.delivery.failures";
    public static final String AUDIO_INDEX_OLDEST_SECONDS = "myyoutube.audio.index.oldest.seconds";
    public static final String AUDIO_INDEX_PENDING_CAPPED = "myyoutube.audio.index.pending.capped";
    public static final String AUDIO_INDEX_PENDING_LOWERBOUND = "myyoutube.audio.index.pending.lowerbound";

    public static final String BUSINESS_DRAFTS_CAPPED = "myyoutube.business.drafts.capped";
    public static final String BUSINESS_DRAFTS_LOWERBOUND = "myyoutube.business.drafts.lowerbound";
    public static final String BUSINESS_DURATION = "myyoutube.business.duration";
    public static final String BUSINESS_INFLIGHT = "myyoutube.business.inflight";
    public static final String BUSINESS_OPERATION_ENABLED = "myyoutube.business.operation.enabled";
    public static final String BUSINESS_OPERATIONS = "myyoutube.business.operations";
    public static final String BUSINESS_PUBLICATION_CAPPED = "myyoutube.business.publication.capped";
    public static final String BUSINESS_PUBLICATION_OLDEST_SECONDS = "myyoutube.business.publication.oldest.seconds";
    public static final String BUSINESS_PUBLICATION_PENDING_LOWERBOUND = "myyoutube.business.publication.pending.lowerbound";

    public static final String HOT_SEARCH_ACTIVE_GROUPS = "myyoutube.hot.search.active.groups";
    public static final String HOT_SEARCH_ARCHIVE_INITIALIZED = "myyoutube.hot.search.archive.initialized";
    public static final String HOT_SEARCH_ARCHIVE_LAG_DAYS = "myyoutube.hot.search.archive.lag.days";
    public static final String HOT_SEARCH_CACHE_READS = "myyoutube.hot.search.cache.reads";
    public static final String HOT_SEARCH_DISPLAYED = "myyoutube.hot.search.displayed";
    public static final String HOT_SEARCH_DURATION = "myyoutube.hot.search.duration";
    public static final String HOT_SEARCH_ENABLED = "myyoutube.hot.search.enabled";
    public static final String HOT_SEARCH_HISTORY_EVENTS = "myyoutube.hot.search.history.events";
    public static final String HOT_SEARCH_LAST_PUBLISH_SECONDS = "myyoutube.hot.search.last.publish.seconds";
    public static final String HOT_SEARCH_MODEL_ITEMS = "myyoutube.hot.search.model.items";
    public static final String HOT_SEARCH_PENDING = "myyoutube.hot.search.pending";
    public static final String HOT_SEARCH_PENDING_OLDEST_SECONDS = "myyoutube.hot.search.pending.oldest.seconds";
    public static final String HOT_SEARCH_RECORDS = "myyoutube.hot.search.records";
    public static final String HOT_SEARCH_SNAPSHOT_TTL_SECONDS = "myyoutube.hot.search.snapshot.ttl.seconds";
    public static final String HOT_SEARCH_SUMMARY_OLDEST_SECONDS = "myyoutube.hot.search.summary.oldest.seconds";
    public static final String HOT_SEARCH_SUMMARY_PENDING_DAYS = "myyoutube.hot.search.summary.pending.days";

    public static final String LLM_REQUEST_DURATION = "llm.request.duration";
    public static final String LLM_REQUESTS_ACTIVE = "llm.requests.active";

    public static final String MAINTENANCE_DEFERRED = "myyoutube.maintenance.deferred";

    public static final String MEDIA_CLAIM_SKIPPED = "media.claim.skipped";
    public static final String MEDIA_DELIVERY_FAILURES = "media.delivery.failures";
    public static final String MEDIA_MODEL_DEFERRED = "media.model.deferred";
    public static final String MEDIA_MODEL_TERMINAL = "media.model.terminal";
    public static final String MEDIA_MODEL_UNAVAILABLE_EXHAUSTED = "media.model.unavailable.exhausted";
    public static final String MEDIA_NODE_RETRY_WAIT = "media.node.retry.wait";
    public static final String MEDIA_NODE_WAIT = "media.node.wait";
    public static final String MEDIA_PIPELINE_DURATION = "media.pipeline.duration";
    public static final String MEDIA_PIPELINE_GRAPH_ERRORS = "media.pipeline.graph.errors";
    public static final String MEDIA_PIPELINE_INACTIVE_CAPPED = "media.pipeline.inactive.capped";
    public static final String MEDIA_PIPELINE_INACTIVE_LOWERBOUND = "media.pipeline.inactive.lowerbound";
    public static final String MEDIA_PIPELINE_READY_DURATION = "media.pipeline.ready.duration";
    public static final String MEDIA_PUBLICATION_OLDEST_SECONDS = "media.publication.oldest.seconds";
    public static final String MEDIA_PUBLICATION_PENDING = "media.publication.pending";
    public static final String MEDIA_READY_OLDEST_SECONDS = "media.ready.oldest.seconds";
    public static final String MEDIA_RESULT_DELIVERY_OLDEST_SECONDS = "media.result.delivery.oldest.seconds";
    public static final String MEDIA_RETRY_BATCH_ACTIVE = "media.retry.batch.active";
    public static final String MEDIA_RETRY_BATCH_AGE_SECONDS = "media.retry.batch.age.seconds";
    public static final String MEDIA_RETRY_BATCH_COMPLETED = "media.retry.batch.completed";
    public static final String MEDIA_RETRY_ITEMS = "media.retry.items";
    public static final String MEDIA_REVIEW_QUEUE = "media.review.queue";
    public static final String MEDIA_TASKS = "media.tasks";
    public static final String MEDIA_VIDEO_SAMPLING_ATTEMPTS = "media.video.sampling.attempts";

    public static final String MODEL_CALLS_BLOCKED = "model.calls.blocked";
    public static final String MODEL_CALLS_DURATION = "model.calls.duration";
    public static final String MODEL_CALLS_FAILURES = "model.calls.failures";
    public static final String MODEL_CIRCUIT_TRANSITIONS = "model.circuit.transitions";

    public static final String MQ_CONSUMER_OLDEST_SECONDS = "mq.consumer.oldest.seconds";
    public static final String MQ_CONSUMER_PENDING = "mq.consumer.pending";
    public static final String MQ_DLQ_PROBE_FAILURES = "mq.dlq.probe.failures";
    public static final String MQ_DLQ_RETAINED = "mq.dlq.retained";

    public static final String NOTIFICATION_DELIVERY_FAILURES = "myyoutube.notification.delivery.failures";
    public static final String NOTIFICATION_DELIVERY_SUCCESSES = "myyoutube.notification.delivery.successes";

    public static final String PIPELINE_INGEST_BATCHES = "pipeline.ingest.batches";
    public static final String PIPELINE_INGEST_BULK_DURATION = "pipeline.ingest.bulk.duration";
    public static final String PIPELINE_INGEST_DLQ_CLASSIFIED = "pipeline.ingest.dlq.classified";
    public static final String PIPELINE_INGEST_DLQ_TRANSFERS = "pipeline.ingest.dlq.transfers";
    public static final String PIPELINE_INGEST_DUPLICATES = "pipeline.ingest.duplicates";
    public static final String PIPELINE_INGEST_FAILURES = "pipeline.ingest.failures";
    public static final String PIPELINE_LOGS_DELIVERY_SECONDS = "pipeline.logs.delivery.seconds";
    public static final String PIPELINE_LOGS_PENDING = "pipeline.logs.pending";
    public static final String PIPELINE_LOGS_STALLED_SECONDS = "pipeline.logs.stalled.seconds";
    public static final String PIPELINE_METRICS_SAMPLE_AGE_SECONDS = "pipeline.metrics.sample.age.seconds";
    public static final String PIPELINE_METRICS_SAMPLE_ENABLED = "pipeline.metrics.sample.enabled";
    public static final String PIPELINE_METRICS_SAMPLE_FAILURES = "pipeline.metrics.sample.failures";
    public static final String PIPELINE_METRICS_SAMPLE_LAST_SUCCESS_SECONDS = "pipeline.metrics.sample.last.success.seconds";
    public static final String PIPELINE_METRICS_SAMPLE_SUCCESS = "pipeline.metrics.sample.success";
    public static final String PIPELINE_OUTBOX_BACKLOG = "pipeline.outbox.backlog";
    public static final String PIPELINE_OUTBOX_BACKLOG_CAPPED = "pipeline.outbox.backlog.capped";
    public static final String PIPELINE_OUTBOX_CLEANED = "pipeline.outbox.cleaned";
    public static final String PIPELINE_OUTBOX_CLEANUP_FAILURES = "pipeline.outbox.cleanup.failures";
    public static final String PIPELINE_OUTBOX_DELIVERY_FAILURES = "pipeline.outbox.delivery.failures";
    public static final String PIPELINE_OUTBOX_MONITORING_ENABLED = "pipeline.outbox.monitoring.enabled";
    public static final String PIPELINE_OUTBOX_OLDEST_SECONDS = "pipeline.outbox.oldest.seconds";
    public static final String PIPELINE_OUTBOX_QUARANTINED = "pipeline.outbox.quarantined";
    public static final String PIPELINE_OUTBOX_SCAN_FAILURES = "pipeline.outbox.scan.failures";
    public static final String PIPELINE_OUTBOX_SENT = "pipeline.outbox.sent";

    public static final String PUBLICATION_CLAIM_MISSED = "publication.claim.missed";
    public static final String PUBLICATION_FAILED_TOTAL = "publication.failed.total";
    public static final String PUBLICATION_NOTIFICATION_FAILED = "publication.notification.failed";
    public static final String PUBLICATION_SCAN_DURATION = "publication.scan.duration";
    public static final String PUBLICATION_SCAN_FAILED = "publication.scan.failed";

    public static final String PUSH_DELIVERY_FAILURES = "myyoutube.push.delivery.failures";

    public static final String RECOMMEND_FALLBACKS = "myyoutube.recommend.fallbacks";

    public static final String SEARCH_RECALL_FAILURES = "myyoutube.search.recall.failures";
    public static final String SEARCH_REFILL_FAILURES = "myyoutube.search.refill.failures";
    public static final String SEARCH_REFILL_ROUNDS = "myyoutube.search.refill.rounds";

    public static final String UPLOAD_EVENT_REPAIRS = "myyoutube.upload.event.repairs";
}
