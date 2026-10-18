"""仅在官方源码与已安装的 1.3.0 wheel 一致时复现协议文件修复。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile


def prepare(args):
    expected = args.checksum.read_text().split()[0]
    if hashlib.sha512(args.source.read_bytes()).hexdigest() != expected:
        raise RuntimeError('Official source SHA-512 mismatch')
    if args.output.exists():
        raise RuntimeError('Choose a fresh output directory; installed packages are never overwritten')
    metadata = args.wheel / 'apache_skywalking-1.3.0.dist-info'
    if not metadata.is_dir() or '\nVersion: 1.3.0\n' not in (metadata / 'METADATA').read_text():
        raise RuntimeError('An unpacked Python Agent 1.3.0 wheel is required')
    with tempfile.TemporaryDirectory(prefix='python-agent-build-') as temporary:
        root = Path(temporary).resolve()
        with tarfile.open(args.source) as archive:
            for entry in archive.getmembers():
                path = (root / entry.name).resolve()
                if (path != root and root not in path.parents) or entry.issym() or entry.islnk():
                    raise RuntimeError('Unexpected source archive path')
            archive.extractall(root)
        source = root / 'skywalking'
        files = [path for path in source.rglob('*.py') if path.relative_to(source).parts[0] != 'protocol']
        installed_files = {path.relative_to(args.wheel / 'skywalking') for path in (args.wheel / 'skywalking').rglob('*.py')
                           if path.relative_to(args.wheel / 'skywalking').parts[0] != 'protocol'}
        if {path.relative_to(source) for path in files} != installed_files:
            raise RuntimeError('Source and wheel contain different Python modules')
        for path in files:
            installed = args.wheel / 'skywalking' / path.relative_to(source)
            if not installed.is_file() or path.read_bytes() != installed.read_bytes():
                raise RuntimeError('Source differs from the 1.3.0 wheel: ' + str(path.relative_to(source)))
        cli_files = list((root / 'sw_python').rglob('*.py'))
        for path in cli_files:
            installed = args.wheel / 'sw_python' / path.relative_to(root / 'sw_python')
            if not installed.is_file() or path.read_bytes() != installed.read_bytes():
                raise RuntimeError('CLI source differs from the 1.3.0 wheel')
        env = os.environ.copy()
        env['PYTHONPATH'] = str(args.wheel)
        subprocess.run([str(args.python), 'tools/grpc_code_gen.py'], cwd=root, env=env, check=True)
        if not (source / 'protocol/language_agent/Tracing_pb2.py').exists():
            raise RuntimeError('Official protocol generator did not produce tracing stubs')
        args.output.mkdir(parents=True)
        for name in ('skywalking', 'sw_python'):
            shutil.copytree(root / name, args.output / name, ignore=shutil.ignore_patterns('__pycache__'))
        names = ['apache_skywalking-1.3.0.dist-info', 'wrapt', 'packaging']
        names += [path.name for pattern in ('wrapt-*.dist-info', 'packaging-*.dist-info') for path in args.wheel.glob(pattern)]
        for name in names:
            if (args.wheel / name).exists():
                shutil.copytree(args.wheel / name, args.output / name, ignore=shutil.ignore_patterns('*.so', '__pycache__'))
        report = {'version': '1.3.0', 'verifiedSourceSha512': expected, 'matchingSourceFiles': len(files),
                  'matchingCliFiles': len(cli_files),
                  'protocolGenerator': 'official tools/grpc_code_gen.py', 'nativeDependencies': 'Use each service virtual environment; no binary extensions copied',
                  'sourceMetadataCaution': 'Downloaded 1.3.0 source declares 1.2.0 in pyproject.toml; code must exactly match the wheel',
                  'distribution': 'Locally repaired test bundle; not an unmodified PyPI wheel'}
        (args.output / 'build-report.json').write_text(json.dumps(report, indent=2))
        print(json.dumps(report))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--checksum', type=Path, required=True)
    parser.add_argument('--wheel', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--python', type=Path, default=Path(sys.executable))
    arguments = parser.parse_args()
    for field in ('source', 'checksum', 'wheel', 'output', 'python'):
        setattr(arguments, field, getattr(arguments, field).resolve())
    prepare(arguments)
