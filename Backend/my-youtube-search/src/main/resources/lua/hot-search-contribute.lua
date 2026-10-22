local expiry = tonumber(ARGV[1])
redis.call('PFADD', KEYS[1], unpack(ARGV, 3))
redis.call('PEXPIREAT', KEYS[1], expiry)
-- 旧小时重放不能缩短仍有较新桶的组元信息寿命。
expiry = math.max(expiry, redis.call('PEXPIRETIME', KEYS[2]))
redis.call('SET', KEYS[2], ARGV[2], 'PXAT', expiry)
return expiry
