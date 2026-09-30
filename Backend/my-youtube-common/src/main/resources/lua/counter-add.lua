---@diagnostic disable: undefined-global
-- 写路径：累积增量 + 登记待对账 id。
-- KEYS[1]=pending  KEYS[2]=dirty
-- ARGV[1]=delta    ARGV[2]=id  ARGV[3]=dirty TTL
redis.call('INCRBY', KEYS[1], ARGV[1])
redis.call('SADD', KEYS[2], ARGV[2])
redis.call('EXPIRE', KEYS[2], ARGV[3])
return 1
