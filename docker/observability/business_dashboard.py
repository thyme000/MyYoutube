"""配置 Kibana 内容加工与审核看板，复用现有事件索引。"""
import argparse
import json
import urllib.error

DATA_VIEW = 'myyoutube-pipeline-events'
DASHBOARD_ID = 'myyoutube-business-flow'
VISUALIZATION_ID = 'myyoutube-business-flow-graph'
SEARCH_ID = 'myyoutube-business-flow-events'
NODES = [
    ('SUBMIT', '提交内容', 0, 1), ('COVER', '封面', 1, 0),
    ('ASR', '语音识别', 1, 1), ('OCR', '文字识别', 1, 2),
    ('SUMMARY', '生成摘要', 2, 1.5), ('TAG', '生成标签', 3, 1.5),
    ('PIPELINE', '加工汇总', 4, 1.5), ('STORE', '产物落库', 5, 1.5),
    ('REVIEW', '自动审核', 6, 1.5), ('CONTENT', '审核结果', 7, 1.5),
]
EDGES = [('SUBMIT', 'COVER'), ('SUBMIT', 'ASR'), ('SUBMIT', 'OCR'),
         ('ASR', 'SUMMARY'), ('OCR', 'SUMMARY'), ('SUMMARY', 'TAG'),
         ('TAG', 'PIPELINE'), ('COVER', 'PIPELINE'), ('PIPELINE', 'STORE'),
         ('STORE', 'REVIEW'), ('REVIEW', 'CONTENT')]
LABELS = {'SUCCESS': '成功', 'COMPLETED': '已完成', 'APPROVED': '审核通过',
          'RUNNING': '执行中', 'PROCESSING': '加工中', 'AUTO_RUNNING': '审核中',
          'READY': '待执行', 'PENDING': '等待依赖', 'AUTO_PENDING': '待审核',
          'RETRY_WAIT': '等待重试', 'WAITING_ARTIFACTS': '需补跑',
          'MANUAL_PENDING': '待人工审核', 'WAITING_PIPELINE': '等待加工',
          'UPLOADING': '等待上传', 'EXPIRED': '已过期', 'SUBMITTED': '已提交',
          'WAITING_PREPARATION': '等待准备', 'WAITING_SUBMISSION': '等待提交', 'DEAD': '重试耗尽', 'BLOCKED': '被阻塞', 'FAILED': '失败',
          'REJECTED': '审核拒绝', 'UNKNOWN': '未观测', 'AMBIGUOUS': '多个任务', 'INCOMPLETE': '结果截断'}


def event_query(group="pipelineId"):
    fields = ['@timestamp', 'event', 'aggregate', 'state', 'attempt', 'taskId',
              'contentId', 'draftId', 'preparationId', 'mediaVersion', 'pipelineId', 'trace', 'failure', 'nextRetryAt']
    return {'size': 0, 'aggs': {'pipelines': {
        'terms': {'field': group, 'size': 20, 'order': {'latest': 'desc'}},
        'aggs': {'latest': {'max': {'field': '@timestamp'}}, 'subjects': {
            'multi_terms': {'terms': [{'field': 'aggregate.type'}, {'field': 'aggregate.id'}], 'size': 32},
            'aggs': {'latest': {'top_hits': {'size': 1, 'sort': [{'event.sequence': 'desc'}], '_source': fields}}}
        }}
    }}}


def formula(field, expression):
    return {'type': 'formula', 'as': field, 'expr': expression}


def text_mark(text, x, y, size=12, color='#475569', **extra):
    values = {'text': text if isinstance(text, dict) else {'value': text},
              'x': x if isinstance(x, dict) else {'value': x}, 'y': {'value': y},
              'fontSize': {'value': size}, 'fill': {'value': color}, **extra}
    return {'type': 'text', 'encode': {'update': values}}


def build_spec(upload=False):
    nodes = [('DRAFT', '草稿状态', 0, 1), ('PREPARE', '媒体准备', 1, 1),
             ('READY', '可提交', 2, 1), ('SUBMITTED', '已提交', 3, 1)] if upload else NODES
    edges = [('DRAFT', 'PREPARE'), ('PREPARE', 'READY'), ('READY', 'SUBMITTED')] if upload else EDGES
    business_id = 'draftId' if upload else 'pipelineId'
    title = '上传与媒体准备' if upload else '内容加工与审核'
    active = "(length(data('selectedPipeline')) ? data('selectedPipeline')[0].key : '')"
    status = "length(data('selectedPipeline')) && data('selectedPipeline')[0].subjects.sum_other_doc_count > 0 ? 'INCOMPLETE' : "
    status += "!datum.snapshot ? 'UNKNOWN' : datum.snapshot.count > 1 ? 'AMBIGUOUS' : "
    status += "datum.stage === 'SUBMIT' ? 'SUCCESS' : datum.stage === 'STORE' ? "
    status += "(indexof(['AUTO_PENDING','AUTO_RUNNING','MANUAL_PENDING','APPROVED','REJECTED'], datum.snapshot.ev.state.to) >= 0 ? 'SUCCESS' : 'WAITING_PIPELINE') : datum.snapshot.ev.state.to"
    if upload:
        status = "length(data('selectedPipeline')) && data('selectedPipeline')[0].subjects.sum_other_doc_count > 0 ? 'INCOMPLETE' : "
        status += "!datum.snapshot ? 'UNKNOWN' : datum.snapshot.count > 1 ? 'AMBIGUOUS' : "
        status += "datum.stage === 'READY' ? (indexof(['READY','SUBMITTED'],datum.snapshot.ev.state.to)>=0 ? 'SUCCESS' : indexof(['FAILED','EXPIRED'],datum.snapshot.ev.state.to)>=0 ? datum.snapshot.ev.state.to : 'WAITING_PREPARATION') : "
        status += "datum.stage === 'SUBMITTED' ? (datum.snapshot.ev.state.to==='SUBMITTED' ? 'SUCCESS' : indexof(['FAILED','EXPIRED'],datum.snapshot.ev.state.to)>=0 ? datum.snapshot.ev.state.to : 'WAITING_SUBMISSION') : datum.snapshot.ev.state.to"
    category = "indexof(['SUCCESS','COMPLETED','APPROVED'],datum.status)>=0 ? 'success' : "
    category += "indexof(['RUNNING','PROCESSING','AUTO_RUNNING'],datum.status)>=0 ? 'running' : "
    category += "indexof(['DEAD','BLOCKED','FAILED','REJECTED','EXPIRED'],datum.status)>=0 ? 'failed' : "
    category += "indexof(['UNKNOWN','AMBIGUOUS','INCOMPLETE'],datum.status)>=0 ? 'unknown' : 'waiting'"
    if upload:
        category = "datum.stage==='DRAFT' && indexof(['READY','SUBMITTED'],datum.status)>=0 ? 'success' : " + category
    spec = {
        '$schema': 'https://vega.github.io/schema/vega/v5.json',
        'description': '按聚合内事件序号和当前媒体版本还原上传准备状态。' if upload else '按聚合内事件序号还原内容加工与审核状态。',
        'autosize': {'type': 'fit', 'contains': 'padding'},
        'padding': 16, 'background': '#f8fafc',
        'config': {'kibana': {'restoreSignalValuesOnRefresh': True, 'renderer': 'svg'},
                   'text': {'font': 'sans-serif'}},
        'signals': [
            {'name': 'picked', 'value': '', 'on': [{'events': '@pipelineChoice:click', 'update': 'datum.key'}]},
            {'name': 'selectedStage', 'value': 'DRAFT' if upload else 'CONTENT', 'on': [{'events': '@node:click', 'update': 'datum.stage'}]},
            {'name': 'filterSelection', 'value': None, 'on': [{'events': '@filterButton:click',
                'update': f"{active} ? kibanaAddFilter({{term:{{{business_id}:{active}}}}},'myyoutube-pipeline-events','流程 '+{active}) : null"}]},
            {'name': 'compact', 'update': 'width < 1100'},
            {'name': 'left', 'update': 'compact ? 0 : 230'},
            {'name': 'step', 'update': '(width-left-12)/(compact ? 4 : 8)'},
            {'name': 'card', 'update': 'max(65,step-15)'},
        ],
        'data': [
            {'name': 'response', 'url': {'%context%': True, '%timefield%': '@timestamp',
                                        'index': 'myyoutube-pipeline-events-*', 'body': event_query(business_id)}},
            {'name': 'pipelines', 'source': 'response', 'transform': [
                {'type': 'flatten', 'fields': ['aggregations.pipelines.buckets'], 'as': ['bucket']},
                formula('key', 'datum.bucket.key'), formula('subjects', 'datum.bucket.subjects'),
                formula('last', 'datum.bucket.latest.value'),
                {'type': 'window', 'ops': ['row_number'], 'as': ['row']}]},
            {'name': 'selectedPipeline', 'source': 'pipelines', 'transform': [{'type': 'filter',
                'expr': "datum.key === (indata('pipelines','key',picked) ? picked : length(data('pipelines')) ? data('pipelines')[0].key : '')"}]},
            {'name': 'choices', 'source': 'pipelines', 'transform': [{'type': 'filter', 'expr': '!compact'}]},
            {'name': 'snapshots', 'source': 'selectedPipeline', 'transform': [
                {'type': 'flatten', 'fields': ['subjects.buckets'], 'as': ['subject']},
                formula('ev', 'datum.subject.latest.hits.hits[0]._source'),
                formula('stage', "datum.ev.aggregate.type === 'TASK' ? split(datum.ev.event.code,'_')[0] : datum.ev.aggregate.type"),
                {'type': 'joinaggregate', 'groupby': ['stage'], 'ops': ['count'], 'as': ['count']}]},
            {'name': 'nodes', 'values': [{'stage': s, 'label': label, 'col': col, 'row': row,
                                         'lookup': ('DRAFT' if s in ['READY', 'SUBMITTED'] else s) if upload else ('CONTENT' if s in ['SUBMIT', 'STORE'] else s)}
                                        for s, label, col, row in nodes], 'transform': [
                {'type': 'lookup', 'from': 'snapshots', 'key': 'stage', 'fields': ['lookup'], 'as': ['snapshot']},
                formula('status', status), formula('category', category),
                formula('statusLabel', json.dumps(LABELS, ensure_ascii=False) + '[datum.status] || datum.status'),
                formula('x', 'left + (compact && datum.col>=4 ? datum.col-4 : datum.col)*step'),
                formula('y', '132 + (compact && datum.col>=4 ? 3.5 : datum.row)*88'),
                formula('attempt', "datum.snapshot && isValid(datum.snapshot.ev.attempt) ? datum.snapshot.ev.attempt : null"),
            ]},
            {'name': 'edges', 'values': [{'source': a, 'target': b} for a, b in edges], 'transform': [
                {'type': 'lookup', 'from': 'nodes', 'key': 'stage', 'fields': ['source', 'target'], 'as': ['a', 'b']},
                formula('down', "datum.source==='COVER' || compact && datum.source==='TAG'"),
                formula('x1', "datum.source==='COVER' ? (compact ? datum.a.x : datum.a.x+card/2) : compact && datum.source==='TAG' ? datum.a.x+card/2 : datum.a.x+card"),
                formula('y1', "datum.source==='COVER' ? (compact ? datum.a.y+30 : datum.a.y) : compact && datum.source==='TAG' ? datum.a.y+60 : datum.a.y+30"),
                formula('x2', 'datum.down ? datum.b.x+card/2 : datum.b.x-4'),
                formula('y2', 'datum.down ? datum.b.y-4 : datum.b.y+30'),
                formula('path', "datum.source==='COVER' ? (compact ? 'M'+datum.x1+','+datum.y1+' H'+(step-7)+' V400 H'+datum.x2+' V'+datum.y2 : 'M'+datum.x1+','+datum.y1+' V110 H'+datum.x2+' V'+datum.y2) : compact && datum.source==='TAG' ? 'M'+datum.x1+','+datum.y1+' V400 H'+datum.x2+' V'+datum.y2 : 'M'+datum.x1+','+datum.y1+' C'+((datum.x1+datum.x2)/2)+','+datum.y1+' '+((datum.x1+datum.x2)/2)+','+datum.y2+' '+datum.x2+','+datum.y2")]},
            {'name': 'detail', 'source': 'nodes', 'transform': [{'type': 'filter', 'expr': 'datum.stage === selectedStage'}]},
            {'name': 'legend', 'values': [{'label': label, 'category': category, 'col': i}
                                         for i, (label, category) in enumerate([
                                             ('成功', 'success'), ('执行中', 'running'), ('等待 / 重试 / 人工', 'waiting'),
                                             ('失败 / 阻塞', 'failed'), ('未观测', 'unknown')])]},
        ],
        'scales': [
            {'name': 'color', 'type': 'ordinal', 'domain': ['success', 'running', 'waiting', 'failed', 'unknown'],
             'range': ['#15803d', '#2563eb', '#b45309', '#dc2626', '#64748b']},
            {'name': 'tint', 'type': 'ordinal', 'domain': ['success', 'running', 'waiting', 'failed', 'unknown'],
             'range': ['#f0fdf4', '#eff6ff', '#fffbeb', '#fef2f2', '#f1f5f9']},
        ],
        'marks': [
            text_mark(title, 0, 24, 22, '#0f172a', fontWeight={'value': 'bold'}),
            text_mark({'signal': f"{active} ? '当前流程  '+{active} : '所选时间范围内没有流程事件'"}, 0, 52, 13),
            {'name': 'filterButton', **text_mark('筛选此流程明细', {'signal': 'width-135'}, 52, 12, '#2563eb', cursor={'value': 'pointer'})},
            text_mark({'signal': "compact ? '' : '最近更新的 20 条流程'"}, 0, 90, 12, '#64748b'),
            text_mark({'signal': f"compact ? '顶部用 {business_id} 筛选；点击卡片查看详情。' : '点击左侧切换流程；顶部 KQL 可输入 {business_id} 筛选。'"}, {'signal': 'left'}, 90, 12),
            {'type': 'rect', 'from': {'data': 'choices'}, 'encode': {'update': {
                'x': {'value': 0}, 'y': {'signal': '105+(datum.row-1)*21'}, 'width': {'value': 214},
                'height': {'value': 20}, 'cornerRadius': {'value': 4},
                'fill': {'signal': f"datum.key==={active} ? '#dbeafe' : '#ffffff'"}}}},
            {'name': 'pipelineChoice', 'type': 'text', 'from': {'data': 'choices'}, 'encode': {'update': {
                'text': {'field': 'key'}, 'x': {'value': 8}, 'y': {'signal': '119+(datum.row-1)*21'},
                'fontSize': {'value': 12}, 'font': {'value': 'monospace'}, 'cursor': {'value': 'pointer'},
                'fill': {'signal': f"datum.key==={active} ? '#1d4ed8' : '#475569'"},
                'tooltip': {'signal': "{'流程':datum.key,'最近事件':timeFormat(datum.last,'%Y-%m-%d %H:%M:%S')}"}}}},
            {'type': 'path', 'from': {'data': 'edges'}, 'encode': {'update': {
                'path': {'field': 'path'}, 'stroke': {'value': '#94a3b8'}, 'strokeWidth': {'value': 1.5}}}},
            {'type': 'symbol', 'from': {'data': 'edges'}, 'encode': {'update': {
                'shape': {'signal': "datum.down ? 'triangle-down' : 'triangle-right'"}, 'size': {'value': 45}, 'x': {'field': 'x2'},
                'y': {'field': 'y2'}, 'fill': {'value': '#94a3b8'}}}},
            {'name': 'node', 'type': 'rect', 'from': {'data': 'nodes'}, 'encode': {'update': {
                'x': {'field': 'x'}, 'y': {'field': 'y'}, 'width': {'signal': 'card'}, 'height': {'value': 60},
                'cornerRadius': {'value': 8}, 'fill': {'scale': 'tint', 'field': 'category'},
                'stroke': {'scale': 'color', 'field': 'category'}, 'strokeWidth': {'signal': 'datum.stage===selectedStage ? 2.5 : 1'},
                'cursor': {'value': 'pointer'},
                'tooltip': {'signal': "{'步骤':datum.label,'状态':datum.statusLabel,'事件':datum.snapshot ? datum.snapshot.ev.event.code : '无记录','聚合内序号':datum.snapshot ? datum.snapshot.ev.event.sequence : null,'尝试次数':datum.attempt}"}}}},
            {'type': 'text', 'interactive': False, 'from': {'data': 'nodes'}, 'encode': {'update': {
                'text': {'field': 'label'}, 'x': {'signal': 'datum.x+card/2'}, 'y': {'signal': 'datum.y+21'},
                'align': {'value': 'center'}, 'fontSize': {'value': 12}, 'fontWeight': {'value': 'bold'},
                'fill': {'value': '#0f172a'}, 'limit': {'signal': 'card-8'}}}},
            {'type': 'text', 'interactive': False, 'from': {'data': 'nodes'}, 'encode': {'update': {
                'text': {'field': 'statusLabel'}, 'x': {'signal': 'datum.x+card/2'}, 'y': {'signal': 'datum.y+43'},
                'align': {'value': 'center'}, 'fontSize': {'value': 12}, 'fill': {'scale': 'color', 'field': 'category'},
                'limit': {'signal': 'card-8'}}}},
            {'type': 'symbol', 'from': {'data': 'legend'}, 'encode': {'update': {
                'x': {'signal': 'left+datum.col*130'}, 'y': {'signal': 'compact ? 560 : 380'}, 'size': {'value': 65},
                'fill': {'scale': 'color', 'field': 'category'}}}},
            {'type': 'text', 'from': {'data': 'legend'}, 'encode': {'update': {
                'x': {'signal': 'left+datum.col*130+12'}, 'y': {'signal': 'compact ? 564 : 384'}, 'text': {'field': 'label'},
                'fontSize': {'value': 11}, 'fill': {'value': '#475569'}}}},
            text_mark('节点详情 · 点击上方卡片切换', {'signal': 'left'}, 423, 14, '#0f172a', fontWeight={'value': 'bold'}),
        ],
    }
    if upload:
        datasets = spec['data']
        snapshots = next(item for item in datasets if item['name'] == 'snapshots')
        snapshots['name'] = 'snapshotRows'
        snapshots['transform'].pop()
        position = datasets.index(snapshots) + 1
        datasets[position:position] = [
            {'name': 'draftIdentity', 'source': 'snapshotRows', 'transform': [
                {'type': 'filter', 'expr': "datum.stage==='DRAFT'"}]},
            {'name': 'snapshots', 'source': 'snapshotRows', 'transform': [
                {'type': 'filter', 'expr': "datum.stage==='DRAFT' || datum.stage==='PREPARE' && length(data('draftIdentity'))===1 && isValid(datum.ev.preparationId) && isValid(datum.ev.mediaVersion) && datum.ev.preparationId===data('draftIdentity')[0].ev.preparationId && datum.ev.mediaVersion===data('draftIdentity')[0].ev.mediaVersion"},
                {'type': 'joinaggregate', 'groupby': ['stage'], 'ops': ['count'], 'as': ['count']}]},
        ]
        for signal in spec['signals']:
            if signal['name'] == 'step':
                signal['update'] = '(width-left-12)/4'
    details = [
        "datum.label+'  /  '+datum.statusLabel+(isValid(datum.attempt) ? '  /  尝试 '+datum.attempt+' 次' : '')",
        "datum.snapshot ? '事件 '+datum.snapshot.ev.event.code+'  ·  聚合内序号 '+datum.snapshot.ev.event.sequence+'  ·  '+timeFormat(toDate(datum.snapshot.ev['@timestamp']),'%Y-%m-%d %H:%M:%S') : '当前筛选范围没有对应事件；不能据此判断成功或失败。'",
        "datum.snapshot ? '失败代码：'+(datum.snapshot.ev.failure && datum.snapshot.ev.failure.code || '无')+'  ·  下次重试：'+(datum.snapshot.ev.nextRetryAt || '无') : ''",
        "datum.snapshot ? 'traceId：'+(datum.snapshot.ev.trace && datum.snapshot.ev.trace.id || '未记录') : ''",
    ]
    for i, expression in enumerate(details):
        mark = text_mark({'signal': expression}, {'signal': 'left'}, 447+i*22, 12,
                         limit={'signal': 'width-left-8'})
        mark['from'] = {'data': 'detail'}
        spec['marks'].append(mark)
    spec['marks'].extend([
        text_mark('仅展示当前媒体版本；SUBMITTED 表示已提交加工，尚不代表发布。' if upload else '状态仅覆盖当前筛选范围；未观测不代表成功。箭头表示业务依赖。', {'signal': 'left'}, 548, 11, '#64748b', limit={'signal': 'width-left'}),
        text_mark({'signal': "length(data('selectedPipeline')) && data('selectedPipeline')[0].subjects.sum_other_doc_count > 0 ? '聚合数量达到上限，请缩小时间范围并按业务 ID 精确筛选。' : ''"}, {'signal': 'left'}, 570, 11, '#b45309'),
    ])
    for mark in spec['marks']:
        position = mark['encode']['update'].get('y', {})
        if position.get('value', 0) >= 423:
            mark['encode']['update']['y'] = {'signal': f'{position["value"]} + (compact ? 180 : 0)'}
    return spec


def saved_objects(upload=False):
    dashboard_id = 'myyoutube-upload-flow' if upload else DASHBOARD_ID
    visualization_id = 'myyoutube-upload-flow-graph' if upload else VISUALIZATION_ID
    search_id = 'myyoutube-upload-flow-events' if upload else SEARCH_ID
    title = 'MyYoutube 上传与媒体准备' if upload else 'MyYoutube 业务流程'
    graph_title = 'MyYoutube 上传准备流程图' if upload else 'MyYoutube 加工与审核流程图'
    source = {'indexRefName': 'kibanaSavedObjectMeta.searchSourceJSON.index',
              'query': {'query': '', 'language': 'kuery'}, 'filter': []}
    index_ref = {'name': 'kibanaSavedObjectMeta.searchSourceJSON.index', 'type': 'index-pattern', 'id': DATA_VIEW}
    graph = {'type': 'visualization', 'id': visualization_id, 'attributes': {
        'title': graph_title, 'description': '选择流程并查看各节点最新状态。',
        'visState': json.dumps({'title': graph_title, 'type': 'vega',
                                'params': {'spec': json.dumps(build_spec(upload), ensure_ascii=False)}, 'aggs': []}, ensure_ascii=False),
        'uiStateJSON': '{}', 'kibanaSavedObjectMeta': {'searchSourceJSON': '{}'}}, 'references': []}
    search = {'type': 'search', 'id': search_id, 'attributes': {
        'title': 'MyYoutube 业务事件明细', 'description': '按聚合及序号查看状态变化，可展开查看 trace.id 和失败原因。',
        'columns': ['draftId', 'preparationId', 'mediaVersion', 'pipelineId', 'aggregate.type', 'aggregate.id', 'event.sequence', 'event.code',
                    'state.to', 'attempt', 'failure.code', 'trace.id'], 'sort': [['@timestamp', 'desc']],
        'grid': {}, 'hideChart': True, 'kibanaSavedObjectMeta': {'searchSourceJSON': json.dumps(source)}},
        'references': [index_ref]}
    panels = [{'type': 'visualization', 'panelIndex': '1', 'panelRefName': 'panel_1',
               'gridData': {'x': 0, 'y': 0, 'w': 48, 'h': 30, 'i': '1'}, 'embeddableConfig': {}},
              {'type': 'search', 'panelIndex': '2', 'panelRefName': 'panel_2',
               'gridData': {'x': 0, 'y': 30, 'w': 48, 'h': 16, 'i': '2'}, 'embeddableConfig': {}}]
    dashboard = {'type': 'dashboard', 'id': dashboard_id, 'attributes': {
        'title': title, 'description': '上传与媒体准备；按 draftId 筛选，展示当前准备身份和媒体版本。' if upload else '内容加工与审核状态；输入 pipelineId 筛选，点击流程和节点查看详情。',
        'panelsJSON': json.dumps(panels), 'optionsJSON': json.dumps({'useMargins': True, 'hidePanelTitles': False}),
        'timeRestore': True, 'timeFrom': 'now-24h', 'timeTo': 'now',
        'refreshInterval': {'pause': False, 'value': 15000},
        'kibanaSavedObjectMeta': {'searchSourceJSON': json.dumps(source)}},
        'references': [index_ref, {'name': 'panel_1', 'type': 'visualization', 'id': visualization_id},
                       {'name': 'panel_2', 'type': 'search', 'id': search_id}]}
    return [graph, search, dashboard]


def install(kibana, request, overwrite=False, flow='all'):
    objects = []
    if flow in ('all', 'pipeline'):
        objects.extend(saved_objects())
    if flow in ('all', 'upload'):
        objects.extend(saved_objects(upload=True))
    for item in objects:
        path = f'/api/saved_objects/{item["type"]}/{item["id"]}?overwrite={str(overwrite).lower()}'
        try:
            request(kibana, 'POST', path, {'attributes': item['attributes'], 'references': item['references']})
        except urllib.error.HTTPError as error:
            if overwrite or error.code != 409:
                raise
    print('Business dashboard: ' + kibana.rstrip('/') + '/app/dashboards#/view/' + DASHBOARD_ID)
    print('Upload dashboard: ' + kibana.rstrip('/') + '/app/dashboards#/view/myyoutube-upload-flow')


if __name__ == '__main__':
    from setup_logging import request
    parser = argparse.ArgumentParser()
    parser.add_argument('--kibana', default='http://127.0.0.1:15601')
    parser.add_argument('--overwrite', action='store_true', help='更新本脚本管理的六个对象，覆盖它们的界面编辑')
    parser.add_argument('--flow', choices=['all', 'pipeline', 'upload'], default='all', help='只操作指定流程的三个对象')
    args = parser.parse_args()
    install(args.kibana, request, args.overwrite, args.flow)
