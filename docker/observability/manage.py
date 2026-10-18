"""独立管理观测容器和宿主机摄取 JVM，不启动业务应用。"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import signal
import shlex
import socket
import subprocess
import time
import urllib.request
from setup_logging import setup
from service_targets import diagnose, diagnose_logs, expected, inventory, write_targets

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / 'Backend/my-youtube-observability/target/my-youtube-observability-0.0.1-SNAPSHOT-exec.jar'
LOGS = Path(os.getenv('OBSERVABILITY_LOG_DIRECTORY', str(ROOT / 'logs/observability'))).resolve()
STATE = LOGS / '.runtime/ingest.json'
STACK_STATE = LOGS / '.runtime/stack.json'
ACTIVE_PROFILE = None
PROFILE_PORTS = {
    'logging': (19200, 15601, 15066, 8091),
    'tracing': (19200, 11800, 12800, 17128, 18081),
    'monitoring': (8091, 19090, 19093, 18095, 13000),
    'full': (19200, 15601, 15066, 8091, 11800, 12800, 17128, 18081,
             19090, 19093, 18095, 13000),
}


def check_ports(profile):
    occupied = []
    for port in PROFILE_PORTS[profile]:
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=.2):
                occupied.append(port)
        except ConnectionRefusedError:
            pass
        except OSError as error:
            raise RuntimeError('Cannot check local port ' + str(port)) from error
    if occupied:
        raise RuntimeError('Observability ports already in use: ' + ', '.join(map(str, occupied)))


def compose(profile, *arguments):
    command = ['docker', 'compose', '--project-directory', str(ROOT / 'docker')]
    if (ROOT / '.env').is_file():
        command.extend(['--env-file', str(ROOT / '.env')])
    command.extend(['-p', 'myyoutube-observability', '-f',
                    str(ROOT / 'docker/compose.observability.yml'), '--profile', profile, *arguments])
    subprocess.run(command, cwd=ROOT, check=True)


def identity(pid):
    return subprocess.run(['ps', '-p', str(pid), '-o', 'lstart=', '-o', 'command='],
                          capture_output=True, text=True, check=False).stdout.strip()


def managed():
    if not STATE.exists():
        return None
    state = json.loads(STATE.read_text())
    current = identity(state['pid'])
    if current and current == state['identity'] and str(JAR) in current:
        return state['pid']
    STATE.unlink()
    return None


def start_ingest():
    if managed():
        return
    port = int(os.getenv('INGEST_PORT', '8091'))
    try:
        connection = socket.create_connection(('127.0.0.1', port), timeout=1)
    except ConnectionRefusedError:
        pass
    else:
        connection.close()
        raise RuntimeError('Ingestion port is already in use by an unmanaged process: ' + str(port))
    with (LOGS / '.runtime/ingest-console.txt').open('ab') as console:
        environment = dict(os.environ, INGEST_LOG_FILE=str(LOGS / 'ingest.log'),
                           INGEST_MARKER_DIRECTORY=str(LOGS / 'pending-dlq'))
        process = subprocess.Popen(['java', *shlex.split(os.getenv('INGEST_JAVA_OPTS', '-Xms128m -Xmx256m')), '-jar', str(JAR)], cwd=ROOT, env=environment,
                                   stdout=console, stderr=subprocess.STDOUT, start_new_session=True)
    STATE.write_text(json.dumps({'pid': process.pid, 'identity': identity(process.pid), 'profile': ACTIVE_PROFILE}))
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Ingestion JVM exited; inspect ' + str(LOGS / '.runtime/ingest-console.txt'))
        try:
            with urllib.request.urlopen('http://127.0.0.1:' + os.getenv('INGEST_PORT', '8091') + '/actuator/health', timeout=2) as response:
                if json.load(response).get('status') == 'UP':
                    return
        except OSError:
            pass
        time.sleep(0.5)
    raise TimeoutError('Ingestion JVM is still starting; inspect its log before enabling event delivery')


def stop_ingest():
    pid = managed()
    if pid:
        os.kill(pid, signal.SIGTERM)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if not managed():
                return
            time.sleep(0.25)
        raise TimeoutError('Ingestion JVM did not stop; retained its PID for inspection')


def main():
    global ACTIVE_PROFILE
    parser = argparse.ArgumentParser()
    parser.add_argument('profile', choices=['logging', 'tracing', 'monitoring', 'full'])
    parser.add_argument('action', choices=['prepare', 'up', 'down', 'status', 'doctor'])
    args = parser.parse_args()
    ACTIVE_PROFILE = args.profile
    os.environ.setdefault('INGEST_ELASTICSEARCH', 'http://127.0.0.1:19200')
    (LOGS / '.runtime').mkdir(parents=True, exist_ok=True)
    os.environ['OBSERVABILITY_LOG_DIRECTORY'] = str(LOGS)
    with (LOGS / '.runtime/manage.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        logging_services = ['kibana', 'filebeat'] if args.profile in ('logging', 'full') else []
        monitoring_services = ['local-webhook', 'alertmanager', 'prometheus', 'grafana'] if args.profile in ('monitoring', 'full') else []
        tracing_services = ['skywalking-oap', 'horizon'] if args.profile in ('tracing', 'full') else []
        active = json.loads(STACK_STATE.read_text()) if STACK_STATE.exists() else None
        if args.action in ('up', 'down') and active and active['profile'] != args.profile:
            raise RuntimeError('Stop the active ' + active['profile'] + ' stack before switching to ' + args.profile)
        if args.action in ('up', 'down') and (logging_services or monitoring_services) and managed():
            held_profile = json.loads(STATE.read_text()).get('profile')
            if held_profile != args.profile:
                raise RuntimeError('Stop the managed ' + str(held_profile) + ' JVM before switching to ' + args.profile)
        if args.action == 'prepare':
            if logging_services or monitoring_services:
                subprocess.run(['mvn', '-o', '-f', str(ROOT / 'Backend/pom.xml'), '-pl',
                                'my-youtube-observability', '-am', 'package', '-DskipTests'], check=True, cwd=ROOT)
            print('Observability artifacts prepared; no services started.')
        elif args.action == 'up':
            if (logging_services or monitoring_services) and not JAR.is_file():
                raise FileNotFoundError('Ingestion JAR is missing; run manage.py ' + args.profile + ' prepare')
            if not active:
                check_ports(args.profile)
            if monitoring_services:
                services = inventory()
                enabled, source = expected(services)
                paths = write_targets(services, enabled, LOGS / '.runtime/targets')
                os.environ['PROMETHEUS_JAVA_TARGETS_FILE'] = str(paths['java'])
                os.environ['PROMETHEUS_MODEL_TARGETS_FILE'] = str(paths['model'])
                print('Expected services from', source, ':', ', '.join(sorted(enabled)))
            if logging_services or tracing_services:
                compose(args.profile, 'up', '-d', 'observability-elasticsearch',
                        *(['kibana'] if logging_services else []), *tracing_services)
            if monitoring_services:
                os.environ['PIPELINE_MONITORING_ENABLED'] = 'true'
                os.environ.setdefault('INGEST_BIND_ADDRESS', '0.0.0.0')
            if monitoring_services and not logging_services:
                os.environ['JAVA_TOOL_OPTIONS'] = os.environ.get('JAVA_TOOL_OPTIONS', '') + ' -Dmy-youtube.ingest.enabled=false'
                os.environ.setdefault('MONITOR_LOGS_ENABLED', 'false')
                start_ingest()
            if logging_services:
                setup(os.environ['INGEST_ELASTICSEARCH'],
                      os.getenv('KIBANA_URL', 'http://127.0.0.1:15601'))
                start_ingest()
                compose(args.profile, 'up', '-d', 'filebeat')
            if monitoring_services:
                compose(args.profile, 'up', '-d', *monitoring_services)
            STACK_STATE.write_text(json.dumps({'profile': args.profile}))
            print('Observability ready. Business app profiles and event switches are managed separately.')
        elif args.action == 'down':
            if logging_services or monitoring_services:
                stop_ingest()
            compose(args.profile, 'stop', *logging_services, *tracing_services, *monitoring_services)
            if logging_services or tracing_services:
                compose(args.profile, 'stop', 'observability-elasticsearch')
            STACK_STATE.unlink(missing_ok=True)
            print('Observability stopped; data volumes and business infrastructure retained.')
        elif args.action == 'doctor':
            services = inventory()
            enabled, source = expected(services)
            print('Expected services from', source)
            for name, state, detail in diagnose(services, enabled):
                print(f'{name}: {state}' + (f' ({detail})' if detail else ''))
            if logging_services:
                for name, state, detail in diagnose_logs(services, enabled):
                    print(f'Log source {name}: {state} ({detail})')
            print('Log source checks inspect mount sources only; ES delivery and traces need a sampled business request.')
        else:
            print('Managed ingestion PID:', managed())
            compose(args.profile, 'ps')


if __name__ == '__main__':
    main()
