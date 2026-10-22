local score = 0
for _, key in ipairs(KEYS) do
    score = score + redis.call('PFCOUNT', key)
end
return {score, redis.call('PFCOUNT', unpack(KEYS))}
