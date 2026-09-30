---@diagnostic disable: undefined-global
-- 播放量原子认领
-- KEYS[1]=播放量分片 Hash  ARGV[1]=videoId
-- 返回认领到的增量，无值返回 false
local v = redis.call('HGET', KEYS[1], ARGV[1])
if not v then
  return false
end
if v == '0' then
  redis.call('HDEL', KEYS[1], ARGV[1])
  return false
end
redis.call('HINCRBY', KEYS[1], ARGV[1], -tonumber(v))
return v
