"""配置已核验的 Java Agent 发行包，不启动 JVM。"""
import argparse
import json
from pathlib import Path
import shutil
import zipfile


def prepare(directory):
    with zipfile.ZipFile(directory / 'skywalking-agent.jar') as agent:
        manifest = agent.read('META-INF/MANIFEST.MF').decode()
    if 'Implementation-Version: 9.8.0' not in manifest:
        raise RuntimeError('Java Agent 9.8.0 is required')
    names = ['apm-springmvc-annotation-6.x-plugin', 'apm-spring-webflux-6.x-plugin',
             'apm-spring-cloud-gateway-4.x-plugin', 'apm-trace-ignore-plugin']
    sources = [directory / 'optional-plugins' / (name + '-9.8.0.jar') for name in names]
    if not all(path.is_file() for path in sources):
        raise RuntimeError('The complete official optional-plugins directory is required')
    target = directory / 'config/apm-trace-ignore-plugin.config'
    previous = 'trace.ignore_path=/health,/metrics,/metrics/,/actuator/**\n'
    rule = 'trace.ignore_path=/health,/metrics,/metrics/,/actuator/**,*:/health,*:/metrics,*:/metrics/,*:/actuator/**\n'
    if target.exists() and target.read_text() not in (previous, rule):
        raise RuntimeError('Existing custom ignore rules must be reviewed before replacing them')
    for source in sources:
        shutil.copyfile(source, directory / 'plugins' / source.name)
    target.write_text(rule)
    print(json.dumps({'version': '9.8.0', 'enabledPlugins': names, 'ignoreRule': rule.strip()}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, required=True)
    prepare(parser.parse_args().directory.resolve())
