"""只读检查运行 Grafana 的业务看板与 Prometheus 查询，不生成业务数据。"""
import argparse
import concurrent.futures
import json
import urllib.parse
import urllib.request
from build_business_dashboards import build


def get(url):
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(url, timeout=10) as response:
        return json.load(response)


def check(grafana, prometheus):
    dashboards = list(build().values())
    for expected in dashboards:
        actual = get(grafana.rstrip('/') + '/api/dashboards/uid/' + expected['uid'])['dashboard']
        if actual['title'] != expected['title'] or len(actual['panels']) != len(expected['panels']):
            raise AssertionError('Dashboard provisioning mismatch: ' + expected['uid'])
    expressions = {target['expr'] for dashboard in dashboards for panel in dashboard['panels'] for target in panel.get('targets', [])}
    def query(expression):
        expanded = expression.replace('$__rate_interval', '5m').replace('$__range', '1h').replace('$instance', '.*')
        url = prometheus.rstrip('/') + '/api/v1/query?' + urllib.parse.urlencode({'query': expanded})
        result = get(url)
        if result['status'] != 'success':
            raise AssertionError(expression)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        list(executor.map(query, expressions))
    print(f'Grafana dashboards verified: {len(dashboards)}; Prometheus expressions parsed/executed: {len(expressions)}')
    print('Empty query results are allowed here; this check does not verify business traffic or metric coverage.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--grafana', default='http://127.0.0.1:13000')
    parser.add_argument('--prometheus', default='http://127.0.0.1:19090')
    args = parser.parse_args()
    check(args.grafana, args.prometheus)
