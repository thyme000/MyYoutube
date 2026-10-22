local count = redis.call('ZCOUNT', KEYS[1], ARGV[1], '+inf')
local oldest = redis.call('ZRANGEBYSCORE', KEYS[1], ARGV[1], '+inf', 'WITHSCORES', 'LIMIT', 0, 1)
return {count, #oldest == 0 and -1 or tonumber(oldest[2])}
