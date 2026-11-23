-- 播放会话: 按账号惰性创建, 固定 TTL 不续期——后续观看加入现有会话, 过期后下一次上报生成新会话。
-- 会话 id 是行为流水与播放量的分组原语: 行为表唯一键按它判重, 会话期即播放量窗口。
-- KEYS[1]=会话key  ARGV[1]=候选会话ID  ARGV[2]=TTL秒
local sessionId = redis.call('GET', KEYS[1])
if not sessionId then
    sessionId = ARGV[1]
    redis.call('SET', KEYS[1], sessionId, 'EX', tonumber(ARGV[2]))
end
return sessionId
