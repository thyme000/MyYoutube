---@diagnostic disable: undefined-global
-- refresh 轮换原子化: 检查 + 作废旧 + 签发新, 同一 refresh 并发只有一个请求能成功
-- KEYS[1]=旧 refresh key  KEYS[2]=新 refresh key
-- ARGV[1]=token-ver key 前缀  ARGV[2]=新签发毫秒  ARGV[3]=新 TTL(秒)
local old = redis.call('GET', KEYS[1])
if not old then
  return 'USED'
end
local userId = string.match(old, '^(%d+):')
if not userId then
  return 'USED'
end
local issuedAt = tonumber(string.match(old, ':(%d+)$'))
local ver = redis.call('GET', ARGV[1] .. userId)
if ver and issuedAt and issuedAt < tonumber(ver) then
  redis.call('DEL', KEYS[1])
  return 'REVOKED'
end
redis.call('DEL', KEYS[1])
redis.call('SETEX', KEYS[2], ARGV[3], userId .. ':' .. ARGV[2])
return userId
