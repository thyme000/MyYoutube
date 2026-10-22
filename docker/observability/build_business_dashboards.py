"""生成业务总览和九条链路的 Grafana 面板；不连接或修改运行服务。"""
import json
from pathlib import Path

OUTPUT = Path(__file__).parent / 'monitoring/grafana/dashboards'
DATASOURCE = {'type': 'prometheus', 'uid': 'myyoutube-prometheus'}
SELECTOR = 'job="myyoutube-java",instance=~"$instance"'
CHAINS = {
    'identity': ('注册与登录', '注册、登录、凭证续签、登出、重置密码。成功表示服务方法正常返回，不代表用户索引已同步。'),
    'upload': ('上传与准备', '上传签名 → 对象上传 → PREPARE → 草稿 READY → 提交。签名成功不代表文件上传完成；草稿 SUBMITTED 不代表发布。'),
    'pipeline': ('内容加工', 'COVER ∥ ASR/OCR；ASR ∥ OCR → SUMMARY → TAG；全部加工节点成功才 COMPLETED。空 ASR/OCR 可合法成功，缺产物不可。'),
    'publication': ('审核与发布', '加工产物落库 → 自动/人工审核 → 作者计数、内容文档、通知三个独立分支。审核拒绝是业务决定；方法正常返回不表示审核通过。'),
    'discovery': ('搜索与推荐', '查询 → 召回 → 融合 → 可读结果。空结果单独统计，可能来自深分页或正常无命中；降级可能与正常返回同时发生。'),
    'consumption': ('内容访问与播放记录', '详情、字幕、播放记录保存、下载申请。只反映后端返回；实际首帧、卡顿、完播及下载完成尚需客户端/异步结果数据。'),
    'interaction': ('点赞收藏评论与关注', '统计用户操作正常返回、业务异常、系统异常。幂等重放也算一次调用，异步计数/评论投影完成情况需结合 MQ 与日志。'),
    'messaging': ('聊天与通知', '聊天发送、撤回、已读、会话创建；通知落库与门铃分开统计。发送返回不代表对端收到，门铃返回不代表用户已读。'),
    'maintenance': ('清理与补偿', '过期、草稿清理、准备素材回收、发布补偿调度。一次任务正常返回只表示本轮结束，逐项失败另列为延后次数。'),
}
OUTCOMES = 'success=正常返回；empty=返回空列表/空页；no_change=条件未命中；business_error=BizException（含业务校验，也可能包装基础设施故障）；failure=其他异常或事务回滚。'
BOUNDARY = '统计单位为操作调用/尝试，包含重试和幂等重放，不是去重用户数、内容数或端到端成功率。无流量、未接入、采样失败或过期显示无数据，不补零。'


def metric(name, extra=''):
    return name + '{' + SELECTOR + (',' + extra if extra else '') + '}'


def fresh(name, component, extra=''):
    query = metric(name, extra)
    for suffix, condition in [('enabled', '== 1'), ('success', '== 1'), ('age_seconds', '<= 90')]:
        query += ' and on(instance) (' + metric('pipeline_metrics_sample_' + suffix, 'component="' + component + '"') + ' ' + condition + ')'
    return '(' + query + ')'


def panel(title, expr=None, legend='', unit='short', description='', kind='timeseries'):
    item = {'title': title, 'type': kind, 'description': description,
            'fieldConfig': {'defaults': {'unit': unit, 'noValue': '未观测 / 无样本'}, 'overrides': []},
            'options': {'legend': {'displayMode': 'list', 'placement': 'bottom'}}}
    if expr:
        item.update(datasource=DATASOURCE, targets=[{'refId': 'A', 'expr': expr, 'legendFormat': legend}])
    return item


def note(text):
    return {'title': '口径与排障入口', 'type': 'text', 'options': {'mode': 'markdown', 'content': text}}


def health(component):
    return [panel('采样开关、结果及新鲜度请同时查看', metric('pipeline_metrics_sample_success', 'component="' + component + '"'), '{{instance}}', description='0 为采样失败；无数据表示未接入。'),
            panel('距最近成功采样', metric('pipeline_metrics_sample_age_seconds', 'component="' + component + '"'), '{{instance}}', 's'),
            panel('后台采样启用状态', metric('pipeline_metrics_sample_enabled', 'component="' + component + '"'), '{{instance}}', description='1 已启用，0 关闭。数据面板仅采用已启用、成功且不超过 90 秒的样本。')]


def operation_panels(chain):
    selection = 'chain="' + chain + '"'
    counts = metric('myyoutube_business_operations_total', selection)
    increase = 'increase(' + counts + '[$__rate_interval])'
    accepted = metric('myyoutube_business_operations_total', selection + ',outcome=~"success|empty|no_change"')
    return [
        panel('业务操作接入状态', metric('myyoutube_business_operation_enabled', selection), '{{operation}} · {{instance}}', description='1 表示此实例已登记业务埋点；同时检查服务抓取状态。'),
        panel('各操作完成速率及结果', 'sum by(operation,outcome) (rate(' + counts + '[$__rate_interval]))', '{{operation}} · {{outcome}}', 'ops', BOUNDARY),
        panel('所选时段调用完成量', 'sum by(operation,outcome) (increase(' + counts + '[$__range]))', '{{operation}} · {{outcome}}', description='按计数器增量统计，自动处理进程重启；窗口开始前需要有抓取样本。'),
        panel('正常返回比例（含空结果和条件未命中）', 'sum by(operation) (increase(' + accepted + '[$__rate_interval])) / sum by(operation) (' + increase + ')', '{{operation}}', 'percentunit', '无调用时没有有效比例；不是整条链路成功率。'),
        panel('操作耗时 P95', 'histogram_quantile(0.95, sum by(le,operation) (rate(' + metric('myyoutube_business_duration_seconds_bucket', selection) + '[$__rate_interval])))', '{{operation}}', 's', '同步方法调用至事务最终完成；不包含后续异步业务耗时。'),
        panel('当前在途调用', 'sum by(operation) (' + metric('myyoutube_business_inflight', selection) + ')', '{{operation}}', description='包含等待外层事务完成的操作。'),
        panel('业务异常与系统异常', 'sum by(operation,outcome) (increase(' + metric('myyoutube_business_operations_total', selection + ',outcome=~"business_error|failure"') + '[$__rate_interval]))', '{{operation}} · {{outcome}}', description=OUTCOMES),
    ]


def special(chain):
    if chain == 'upload':
        return [panel('当前草稿状态（有界下限）', 'max by(status) ' + fresh('myyoutube_business_drafts_lowerbound', 'business_backlog'), '{{status}}', description='每状态最多探测 2001 条；达到上限后是下限。READY 是可提交草稿，不是已发布内容。'),
                panel('草稿状态是否超过采样上限', 'max by(status) ' + fresh('myyoutube_business_drafts_capped', 'business_backlog'), '{{status}}'),
                panel('PREPARE 当前任务状态', 'max by(status) ' + fresh('media_tasks', 'tasks', 'type="PREPARE"'), '{{status}}'),
                panel('上传事件补发次数', 'sum(increase(' + metric('myyoutube_upload_event_repairs_total') + '[$__rate_interval]))', description='存储存在但上传事件缺失时，治理任务成功补发的次数。'),
                *health('business_backlog')]
    if chain == 'pipeline':
        return [panel('加工任务状态存量', 'max by(type,status) ' + fresh('media_tasks', 'tasks', 'phase="CONTENT"'), '{{type}} · {{status}}'),
                panel('提交到加工完成 P95', 'histogram_quantile(0.95, sum by(le) (rate(' + metric('media_pipeline_duration_seconds_bucket') + '[$__rate_interval])))', unit='s'),
                panel('READY 最老等待', 'max ' + fresh('media_ready_oldest_seconds', 'tasks'), unit='s'),
                panel('各节点认领等待 P95', 'histogram_quantile(0.95, sum by(le,type) (rate(' + metric('media_node_wait_seconds_bucket') + '[$__rate_interval])))', '{{type}}', 's'),
                panel('加工完成后产物最老待投递时间', 'max ' + fresh('media_result_delivery_oldest_seconds', 'tasks'), unit='s'),
                panel('任务图异常次数', 'sum(increase(' + metric('media_pipeline_graph_errors_total') + '[$__rate_interval]))'),
                *health('tasks')]
    if chain == 'publication':
        return [panel('自动审核和人工审核待处理', 'max by(mode) ' + fresh('media_review_queue', 'review'), '{{mode}}'),
                panel('发布三个分支待完成量（有界下限）', 'max by(branch) ' + fresh('myyoutube_business_publication_pending_lowerbound', 'business_backlog'), '{{branch}}', description='count=作者计数及用户文档同步投递；search=内容文档；notice=审核通知落库。采样前 2000 个未完成事件，同一事件可同时属于多个分支，不能相加作为内容数。'),
                panel('发布各分支最老等待', 'max by(branch) ' + fresh('myyoutube_business_publication_oldest_seconds', 'business_backlog'), '{{branch}}', 's', '采用数据库时钟；截断且未见到某分支时返回未知。'),
                panel('发布分支采样是否截断', 'max ' + fresh('myyoutube_business_publication_capped', 'business_backlog')),
                *health('business_backlog'), *health('review')]
    if chain == 'discovery':
        return [panel('搜索和推荐空页 / 空列表次数', 'sum by(operation) (increase(' + metric('myyoutube_business_operations_total', 'chain="discovery",outcome="empty"') + '[$__rate_interval]))', '{{operation}}', description='返回页为空，不等同于全部召回为空。'),
                panel('搜索召回降级次数', 'sum by(strategy) (increase(' + metric('myyoutube_search_recall_failures_total') + '[$__rate_interval]))', '{{strategy}}'),
                panel('推荐 CF 降级次数', 'sum by(strategy) (increase(' + metric('myyoutube_recommend_fallbacks_total') + '[$__rate_interval]))', '{{strategy}}'),
                *hot_search_panels()]
    if chain == 'messaging':
        return [panel('通知落库与门铃正常返回次数', 'sum by(stage) (increase(' + metric('myyoutube_notification_delivery_successes_total') + '[$__rate_interval]))', '{{stage}}', description='统计 NotificationPublisher 门面的通知；发布审核通知走独立分支积压面板。门铃返回不代表在线接收。'),
                panel('通知落库 / 门铃失败', 'sum by(stage) (increase(' + metric('myyoutube_notification_delivery_failures_total') + '[$__rate_interval]))', '{{stage}}'),
                panel('WS / SSE 本地处理失败', 'sum by(channel) (increase(' + metric('myyoutube_push_delivery_failures_total') + '[$__rate_interval]))', '{{channel}}')]
    if chain == 'maintenance':
        return [panel('逐项清理 / 回收延后次数', 'sum by(stage) (increase(' + metric('myyoutube_maintenance_deferred_total') + '[$__rate_interval]))', '{{stage}}', description='计数为失败尝试，重复失败会重复计入；不等于待回收对象存量。'),
                panel('上传事件成功补发次数', 'sum(increase(' + metric('myyoutube_upload_event_repairs_total') + '[$__rate_interval]))'),
                panel('发布最老待完成时间', 'max ' + fresh('media_publication_oldest_seconds', 'review'), unit='s'),
                panel('Outbox 未发送及隔离存量', 'max by(service,status) ' + fresh('pipeline_outbox_backlog', 'outbox'), '{{service}} · {{status}}')]
    return []


def hot_search_panels():
    groups = panel('当前进程上次发布的组数与展示数', metric('myyoutube_hot_search_active_groups'), '{{instance}} · 活跃组',
                   description='复用发布时的计数，不额外读取向量或遍历 Redis；包括未达到展示人数门槛的活跃组。仅为该进程的上次成功发布值，多实例不能相加，需结合最近发布时间。')
    groups['targets'].append({'refId': 'B', 'expr': metric('myyoutube_hot_search_displayed'), 'legendFormat': '{{instance}} · 展示数'})
    return [
        panel('热搜采集结果', 'sum by(outcome) (rate(' + metric('myyoutube_hot_search_records_total') + '[$__rate_interval]))', '{{outcome}}', 'ops', 'accepted=Redis 已接收；filtered=输入被过滤；capacity=分区容量满；rejected=线程池拒绝；failure=写 Redis 失败。不是最终落库量。'),
        panel('热搜缓存读取与降级', 'sum by(kind,outcome) (increase(' + metric('myyoutube_hot_search_cache_reads_total') + '[$__rate_interval]))', '{{kind}} · {{outcome}}', description='hot=榜单，named=向量复用；miss 是正常无命中，timeout/rejected/failure 是降级，budget 是调用前预算已耗尽。'),
        panel('热搜后台操作失败', 'sum by(operation) (increase(' + metric('myyoutube_hot_search_duration_seconds_count', 'outcome="failure"') + '[$__rate_interval]))', '{{operation}}', description='refresh 正常返回可能仍有部分词或落库延后；同时检查模型、贡献、写库、确认和汇总各子步骤。'),
        panel('热搜后台操作耗时 P95', 'histogram_quantile(0.95, sum by(le,operation) (rate(' + metric('myyoutube_hot_search_duration_seconds_bucket') + '[$__rate_interval])))', '{{operation}}', 's', '含成功和失败尝试，桶上界 120 秒；超出范围的耗时需结合日志与 duration sum/count。'),
        panel('热搜模型条目处理结果', 'sum by(operation,outcome) (increase(' + metric('myyoutube_hot_search_model_items_total') + '[$__rate_interval]))', '{{operation}} · {{outcome}}', description='仅统计已返回的模型批次；unassigned 表示该批未返回有效向量或分组的输入条目，完整调用失败见操作失败面板。重试会重复计数。'),
        panel('热搜历史写入与确认尝试', 'sum by(outcome) (increase(' + metric('myyoutube_hot_search_history_events_total') + '[$__rate_interval]))', '{{outcome}}', description='committed=批量事务返回成功；acknowledged=随后 Redis 确认成功；deferred=保留重试；excluded=归组前被过滤并确认。包含幂等重放，四项不能相加为去重事件总量。'),
        panel('热搜待处理事件', 'max ' + fresh('myyoutube_hot_search_pending', 'hot_search'), description='各输入分区有效期内事件数量之和，同一 Redis 的多副本用 max，不能相加。采样不包含已过三天边界的记录。'),
        panel('热搜最老待处理时间', 'max ' + fresh('myyoutube_hot_search_pending_oldest_seconds', 'hot_search'), unit='s'),
        panel('热搜共享榜单剩余有效期', 'min ' + fresh('myyoutube_hot_search_snapshot_ttl_seconds', 'hot_search'), unit='s', description='共享 Redis 快照 TTL；0 表示缺失、过期或没有有效 TTL。未采样、采样失败或超过 90 秒不展示旧值。'),
        panel('热搜每日收尾滞后', 'max ' + fresh('myyoutube_hot_search_archive_lag_days', 'hot_search'), unit='d', description='按北京时间凌晨 4 点计算应收尾日，未初始化为无样本。'),
        panel('热搜待汇总日期数', 'max ' + fresh('myyoutube_hot_search_summary_pending_days', 'hot_search'), description='Redis 待汇总日期数，不是明细行数。'),
        panel('热搜最老汇总延后时间', 'max ' + fresh('myyoutube_hot_search_summary_oldest_seconds', 'hot_search'), unit='s', description='自该归档日次日北京时间 4 点开始计时，汇总提交后确认失败也会保留重试。'),
        panel('当前进程距最近成功发布', 'time() - ' + metric('myyoutube_hot_search_last_publish_seconds'), '{{instance}}', 's', '仅当前进程；其他实例完成发布不会更新本值。共享榜单状态以 TTL 面板为准，首次发布前无样本。'),
        groups,
        *health('hot_search'),
    ]


def dashboard(identity, title, panels):
    y = 0
    for i, item in enumerate(panels):
        item['id'] = i + 1
        if item['type'] == 'text':
            item['gridPos'] = {'x': 0, 'y': y, 'w': 24, 'h': 6}
            y += 6
        else:
            previous = panels[i-1] if i else None
            right = previous and previous['type'] != 'text' and previous['gridPos']['x'] == 0
            item['gridPos'] = {'x': 12 if right else 0, 'y': y-8 if right else y, 'w': 12, 'h': 8}
            if not right:
                y += 8
    return {'uid': identity, 'title': title, 'schemaVersion': 41, 'version': 1, 'refresh': '15s',
            'timezone': 'browser', 'time': {'from': 'now-1h', 'to': 'now'}, 'tags': ['MyYoutube', '业务'],
            'annotations': {'list': []}, 'links': [{'title': '业务总览', 'url': '/d/myyoutube-business-overview', 'includeVars': True, 'keepTime': True},
                {'title': '各链路看板', 'type': 'dashboards', 'tags': ['业务'], 'asDropdown': True, 'includeVars': True, 'keepTime': True},
                {'title': '内容单笔事件', 'url': 'http://127.0.0.1:15601/app/dashboards#/view/myyoutube-business-flow', 'targetBlank': True},
                {'title': '上传准备单笔事件', 'url': 'http://127.0.0.1:15601/app/dashboards#/view/myyoutube-upload-flow', 'targetBlank': True}],
            'templating': {'list': [{'name': 'instance', 'label': '实例', 'type': 'query', 'datasource': DATASOURCE,
                'query': 'label_values(up{job="myyoutube-java"}, instance)', 'refresh': 1, 'multi': True,
                'includeAll': True, 'allValue': '.*', 'current': {'text': 'All', 'value': '$__all'}}]}, 'panels': panels}


def build():
    nav = ' · '.join('[' + title + '](/d/myyoutube-business-' + chain + ')' for chain, (title, _) in CHAINS.items())
    overview = [note(nav + '\n\n' + BOUNDARY + '\n\n' + OUTCOMES),
        panel('业务操作完成速率（不是去重业务数）', 'sum by(chain,outcome) (rate(' + metric('myyoutube_business_operations_total') + '[$__rate_interval]))', '{{chain}} · {{outcome}}'),
        panel('业务异常与系统异常次数', 'sum by(chain,outcome) (increase(' + metric('myyoutube_business_operations_total', 'outcome=~"business_error|failure"') + '[$__rate_interval]))', '{{chain}} · {{outcome}}'),
        panel('已接入的业务操作数', 'count by(chain) (' + metric('myyoutube_business_operation_enabled') + ')', '{{chain}}', description='后端启动时登记；有指标但调用量为零表示已接入且暂无调用，缺指标不能判断健康。'),
        panel('当前在途操作', 'sum by(chain) (' + metric('myyoutube_business_inflight') + ')', '{{chain}}'),
        panel('服务指标抓取状态', metric('up'), '{{service}} · {{instance}}', description='1 可抓取，0 抓取失败；目标未配置时没有数据，不表示服务正常。'),
        panel('上传 / 准备 / 待提交 / 失败草稿（有界下限）', 'max by(status) ' + fresh('myyoutube_business_drafts_lowerbound', 'business_backlog'), '{{status}}'),
        panel('加工重试耗尽 / 阻塞存量', 'max by(type,status) ' + fresh('media_tasks', 'tasks', 'status=~"DEAD|BLOCKED"'), '{{type}} · {{status}}'),
        panel('待自动 / 人工审核', 'max by(mode) ' + fresh('media_review_queue', 'review'), '{{mode}}'),
        panel('发布三个分支待完成（有界下限）', 'max by(branch) ' + fresh('myyoutube_business_publication_pending_lowerbound', 'business_backlog'), '{{branch}}'),
        panel('各后台采样结果', metric('pipeline_metrics_sample_success'), '{{component}} · {{instance}}'),
        panel('各后台采样年龄', metric('pipeline_metrics_sample_age_seconds'), '{{component}} · {{instance}}', 's'),
        panel('后台采样开关', metric('pipeline_metrics_sample_enabled'), '{{component}} · {{instance}}'),
        panel('草稿及发布采样是否截断', 'max by(status) ' + fresh('myyoutube_business_drafts_capped', 'business_backlog') + ' or max ' + fresh('myyoutube_business_publication_capped', 'business_backlog'), '{{status}}', description='1 表示达到上限；总览存量是下限，请进入对应链路排查。')]
    result = {'business-overview.json': dashboard('myyoutube-business-overview', 'MyYoutube 业务总览', overview)}
    for chain, (title, description) in CHAINS.items():
        panels = [note(description + '\n\n' + BOUNDARY + '\n\n' + OUTCOMES)]
        if chain != 'pipeline':
            panels.extend(operation_panels(chain))
        panels.extend(special(chain))
        result['business-' + chain + '.json'] = dashboard('myyoutube-business-' + chain, 'MyYoutube ' + title, panels)
    return result


if __name__ == '__main__':
    for filename, content in build().items():
        (OUTPUT / filename).write_text(json.dumps(content, ensure_ascii=False, indent=2) + '\n')
    print('Generated business overview and 9 chain dashboards')
