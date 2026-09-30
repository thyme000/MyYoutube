---@diagnostic disable: undefined-global
-- 落库路径：回补上一轮已认领但未确认的 inflight，再整体认领本轮 pending。
-- KEYS[1]=pending  KEYS[2]=inflight
-- ARGV[1]=inflight TTL
-- 返回认领到的增量，无增量返回 false
local old = redis.call('GET', KEYS[2])
if old then
  redis.call('INCRBY', KEYS[1], old)
  redis.call('DEL', KEYS[2])
end
local v = redis.call('GET', KEYS[1])
if not v or v == '0' then
  return false
end
redis.call('SET', KEYS[2], v, 'EX', ARGV[1])
redis.call('DEL', KEYS[1])
return v
