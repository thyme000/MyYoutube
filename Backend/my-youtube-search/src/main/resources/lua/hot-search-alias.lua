redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', ARGV[2])
if not redis.call('ZSCORE', KEYS[2], ARGV[5]) and redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[4]) then return 0 end
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
redis.call('ZADD', KEYS[2], tonumber(ARGV[2]) + tonumber(ARGV[3]), ARGV[5])
redis.call('PEXPIRE', KEYS[2], ARGV[3])
return 1
