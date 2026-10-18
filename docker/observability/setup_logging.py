"""仅配置观测专用日志数据流和 Kibana 数据视图。"""
import argparse
import json
import time
import urllib.error
import urllib.request
from business_dashboard import install


def request(base, method, path, data=None):
    body = None if data is None else json.dumps(data).encode()
    headers = {"Content-Type": "application/json", "kbn-xsrf": "observability", "x-elastic-internal-origin": "Kibana"}
    with urllib.request.urlopen(urllib.request.Request(base.rstrip("/") + path, data=body,
                                headers=headers, method=method), timeout=5) as response:
        return json.load(response)


def wait_ready(base, path, timeout=180):
    deadline = time.monotonic() + timeout
    while True:
        try:
            result = request(base, "GET", path)
            if path != "/api/status" or result.get("status", {}).get("overall", {}).get("level") == "available":
                return
        except (OSError, urllib.error.URLError):
            pass
        if time.monotonic() >= deadline:
            raise TimeoutError("Service not ready: " + base)
        time.sleep(1)


def setup(es, kibana):
    wait_ready(es, "/")
    request(es, "PUT", "/_ingest/pipeline/myyoutube-log-timing", {"processors": [
        {"set": {"field": "event.ingested", "value": "{{{_ingest.timestamp}}}"}},
        {"script": {"lang": "painless", "source": "ctx.logDeliverySeconds = Math.max(0.0, (ZonedDateTime.parse(ctx.event.ingested).toInstant().toEpochMilli() - ZonedDateTime.parse(ctx['@timestamp']).toInstant().toEpochMilli()) / 1000.0);"}}]})
    request(es, "PUT", "/_ilm/policy/myyoutube-logs-retention", {"policy": {"phases": {
        "hot": {"actions": {"rollover": {"max_age": "1d", "max_primary_shard_size": "5gb"}}},
        "delete": {"min_age": "7d", "actions": {"delete": {}}}}}})
    properties = {key: {"type": "keyword"} for key in ("pipelineId", "contentId", "taskId", "draftId",
                  "preparationId", "mediaVersion", "attempt", "taskType")}
    properties.update({"logDeliverySeconds": {"type": "double"}, "event": {"properties": {"ingested": {"type": "date"}}}, "@timestamp": {"type": "date_nanos"}, "message": {"type": "match_only_text"},
                       "trace": {"properties": {"id": {"type": "keyword"}}},
                       "service": {"properties": {"name": {"type": "keyword"}}}})
    request(es, "PUT", "/_index_template/myyoutube-logs-template", {
        "index_patterns": ["logs-myyoutube-*"], "priority": 400, "data_stream": {}, "template": {
            "settings": {"index.lifecycle.name": "myyoutube-logs-retention", "number_of_shards": 1,
                         "number_of_replicas": 0, "refresh_interval": "1s"},
            "mappings": {"properties": properties}}})
    try:
        request(es, "PUT", "/_data_stream/logs-myyoutube-default")
    except urllib.error.HTTPError as error:
        if error.code != 400 or json.load(error).get("error", {}).get("type") != "resource_already_exists_exception":
            raise
    request(es, "PUT", "/logs-myyoutube-default/_mapping", {"properties": properties})
    wait_ready(kibana, "/api/status")
    for identity, title, name in [("myyoutube-pipeline-events", "myyoutube-pipeline-events-*", "MyYoutube pipeline events"),
                                  ("myyoutube-application-logs", "logs-myyoutube-*", "MyYoutube application logs")]:
        request(kibana, "POST", "/api/data_views/data_view", {"override": True,
            "data_view": {"id": identity, "title": title, "name": name, "timeFieldName": "@timestamp"}})
    install(kibana, request)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--es", default="http://127.0.0.1:19200")
    parser.add_argument("--kibana", default="http://127.0.0.1:15601")
    args = parser.parse_args()
    setup(args.es, args.kibana)
    print("Dedicated log data stream and Kibana data views ready")
