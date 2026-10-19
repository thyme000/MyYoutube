for _, key in ipairs(KEYS) do
    local value = redis.call('GET', key)
    if value then return {'EXISTS', value} end
end
for _, key in ipairs(KEYS) do
    redis.call('SET', key, ARGV[1], 'PX', ARGV[2])
end
return {'ACQUIRED'}
