---@diagnostic disable: undefined-global
-- KEYS[1]=年度签到位图，ARGV[1]=签到日期在年内的位下标
-- 缓存缺失时交由调用方重建整年，避免只写入当天一位。
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
redis.call('SETBIT', KEYS[1], ARGV[1], 1)
return 1
