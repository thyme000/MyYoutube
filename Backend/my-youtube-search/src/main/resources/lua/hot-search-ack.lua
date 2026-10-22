local removed = redis.call('ZREM', KEYS[1], unpack(ARGV))
redis.call('DEL', unpack(KEYS, 2))
return removed
