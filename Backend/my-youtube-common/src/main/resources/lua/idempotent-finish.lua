local completed = 0
for _, key in ipairs(KEYS) do
    if redis.call('GET', key) == ARGV[1] then
        if tonumber(ARGV[3]) == 0 then
            redis.call('DEL', key)
        else
            redis.call('SET', key, ARGV[2], 'PX', ARGV[3])
        end
        completed = completed + 1
    end
end
return completed
