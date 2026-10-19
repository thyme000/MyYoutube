for _, key in ipairs(KEYS) do
    if redis.call('GET', key) ~= ARGV[1] then return 0 end
end
for _, key in ipairs(KEYS) do
    redis.call('PEXPIRE', key, ARGV[2])
end
return 1
