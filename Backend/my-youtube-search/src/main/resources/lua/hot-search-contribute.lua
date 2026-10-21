local cutoff = tonumber(ARGV[1]) - tonumber(ARGV[2])
local eventTime = tonumber(ARGV[3])
if eventTime <= cutoff or eventTime > tonumber(ARGV[1]) then return 0 end
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff)
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', cutoff)
if redis.call('EXISTS', KEYS[2]) == 0 and redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[7]) then return 0 end
if not redis.call('ZSCORE', KEYS[1], ARGV[4]) and redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[8]) then return 0 end
redis.call('ZADD', KEYS[1], 'GT', eventTime, ARGV[4])
local latest = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES')
local expiry = tonumber(latest[2]) + tonumber(ARGV[2])
redis.call('PEXPIREAT', KEYS[1], expiry)
redis.call('SET', KEYS[2], ARGV[5], 'PXAT', expiry)
redis.call('ZADD', KEYS[3], 'GT', latest[2], ARGV[6])
redis.call('SET', KEYS[4], ARGV[6], 'PXAT', expiry)
return 1
