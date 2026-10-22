local closed = redis.call('GET', KEYS[1])
if closed and closed >= ARGV[2] then return 0 end
if closed ~= ARGV[1] then return -1 end
-- 先登记汇总任务；日期推进后即使进程退出，也能在后续调度重算。
redis.call('ZADD', KEYS[2], ARGV[3], ARGV[2])
redis.call('SET', KEYS[1], ARGV[2])
return 1
