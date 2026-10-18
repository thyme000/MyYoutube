"""根据服务目录和本机预期运行清单生成抓取目标。"""
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
DIRECTORY = Path(__file__).resolve().parent
NAME = re.compile(r"[a-z][a-z0-9-]*\Z")
TARGET = re.compile(r"[A-Za-z0-9_.-]+:[1-9][0-9]{0,4}\Z")


def inventory():
    services = json.loads((DIRECTORY / 'services.json').read_text())
    names = set()
    identities = set()
    for service in services:
        name, identity, kind, target = service['name'], service['service'], service['kind'], service['target']
        if (not NAME.fullmatch(name) or name in names or not NAME.fullmatch(identity)
                or identity in identities or kind not in ('java', 'model')
                or not TARGET.fullmatch(target) or int(target.rsplit(':', 1)[1]) > 65535):
            raise ValueError('Invalid or duplicate observability service: ' + name)
        names.add(name)
        identities.add(identity)
    return services


def expected(services):
    override = os.getenv('OBSERVABILITY_SERVICES_FILE')
    local = Path(override) if override else DIRECTORY / 'services.local.json'
    if override and not local.is_file():
        raise FileNotFoundError('Expected service list not found: ' + str(local))
    source = local if local.is_file() else DIRECTORY / 'services.local.example.json'
    enabled = json.loads(source.read_text())['enabled']
    known = {service['name'] for service in services}
    if (not isinstance(enabled, list) or not all(isinstance(name, str) for name in enabled)
            or len(enabled) != len(set(enabled)) or not set(enabled) <= known):
        raise ValueError('Invalid enabled service list in ' + str(source))
    return set(enabled), source


def write_targets(services, enabled, directory):
    directory.mkdir(parents=True, exist_ok=True)
    paths = {}
    for kind, filename in [('java', 'java.yml'), ('model', 'models.yml')]:
        path = directory / filename
        lines = [f'- targets: [{json.dumps(service["target"])}]\n'
                 f'  labels: {{service: {json.dumps(service["service"])}}}\n'
                 for service in services if service['kind'] == kind and service['name'] in enabled]
        path.write_text(''.join(lines) if lines else '[]\n')
        paths[kind] = path
    return paths


def diagnose(services, enabled):
    results = []
    for service in services:
        name = service['name']
        if name not in enabled:
            results.append((name, 'DISABLED', ''))
            continue
        host = service['target'].replace('host.docker.internal:', '127.0.0.1:')
        health = '/actuator/health' if service['kind'] == 'java' else '/health'
        metrics = '/actuator/prometheus' if service['kind'] == 'java' else '/metrics/'
        try:
            with urllib.request.urlopen('http://' + host + health, timeout=2) as response:
                health_body = json.load(response)
                healthy = (health_body.get('status') == 'UP' if service['kind'] == 'java'
                           else health_body.get('ready') is True)
                if response.status != 200 or not healthy:
                    raise ValueError('health response is not ready')
        except (OSError, ValueError, json.JSONDecodeError) as error:
            results.append((name, 'DOWN', type(error).__name__))
            continue
        try:
            with urllib.request.urlopen('http://' + host + metrics, timeout=2) as response:
                body = response.read()
                if response.status != 200 or not body:
                    raise ValueError('metrics response is empty or non-200')
            results.append((name, 'READY', 'health and metrics endpoints reachable'))
        except (OSError, ValueError) as error:
            results.append((name, 'INCOMPLETE', type(error).__name__))
    return results


def diagnose_logs(services, enabled):
    java_logs = Path(os.getenv('OBSERVABILITY_LOG_DIRECTORY', str(ROOT / 'logs/observability'))).resolve()
    results = []
    for service in services:
        if service['name'] not in enabled:
            continue
        if service['kind'] == 'model':
            directory = Path(os.getenv(service['name'].upper() + '_LOG_DIRECTORY',
                                       str(ROOT / service['service'] / 'logs'))).resolve()
            file = directory / (service['service'] + '.log')
        else:
            file = java_logs / ('ingest.log' if service['name'] == 'observability' else service['service'] + '.log')
        if not file.is_file():
            results.append((service['name'], 'MISSING', str(file)))
        elif file.stat().st_size == 0:
            results.append((service['name'], 'EMPTY', str(file)))
        else:
            results.append((service['name'], 'SOURCE_PRESENT', str(file)))
    return results
