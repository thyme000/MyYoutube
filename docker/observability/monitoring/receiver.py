"""只在本机记录 Alertmanager 通知，不接入外部消息渠道。"""
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import threading

LABELS = {'alertname', 'severity', 'job', 'instance', 'service', 'group', 'component', 'kind', 'phase', 'type', 'status', 'mode'}
recent = deque(maxlen=200)
lock = threading.Lock()
log = logging.getLogger('local-webhook')


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, value):
        body = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == '/health':
            return self.reply(200, {'status': 'UP'})
        if self.path == '/notifications':
            with lock:
                return self.reply(200, list(recent))
        self.reply(404, {'error': 'not_found'})

    def do_POST(self):
        if self.path != '/alerts':
            return self.reply(404, {'error': 'not_found'})
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 131072:
                raise ValueError()
            payload = json.loads(self.rfile.read(length))
            if payload.get('status') not in ('firing', 'resolved') or not isinstance(payload.get('alerts'), list):
                raise ValueError()
            alerts = []
            for alert in payload['alerts'][:100]:
                alerts.append({'status': alert['status'], 'fingerprint': str(alert.get('fingerprint', ''))[:128],
                               'labels': {k: str(v)[:256] for k, v in alert.get('labels', {}).items() if k in LABELS},
                               'summary': str(alert.get('annotations', {}).get('summary', ''))[:2048]})
            item = {'receivedAt': datetime.now(timezone.utc).isoformat(), 'status': payload['status'],
                    'groupKey': str(payload.get('groupKey', ''))[:2048], 'alerts': alerts}
            # 分组、去重和重复间隔由 Alertmanager 控制；此处记录每次收到的通知。
            with lock:
                log.info(json.dumps(item, ensure_ascii=False, separators=(',', ':')))
                recent.append(item)
            self.reply(200, {'accepted': len(alerts)})
        except (ValueError, KeyError, TypeError):
            self.reply(400, {'error': 'invalid_notification'})


if __name__ == '__main__':
    directory = Path(os.getenv('WEBHOOK_LOG_DIRECTORY', '/data'))
    directory.mkdir(parents=True, exist_ok=True)
    journal = directory / 'notifications.jsonl'
    if journal.exists():
        with journal.open(encoding='utf-8') as previous:
            for line in previous:
                try:
                    recent.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
    log.setLevel(logging.INFO)
    log.addHandler(RotatingFileHandler(directory / 'notifications.jsonl', maxBytes=10485760, backupCount=3, encoding='utf-8'))
    ThreadingHTTPServer(('0.0.0.0', 8095), Handler).serve_forever()
