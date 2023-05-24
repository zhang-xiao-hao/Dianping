-- 比较线程表示与锁中是否一致，传入的第一个参数会放入KEYS[1]，第二个参数会放入ARGV[1]
if(redis.call('get', KEYS[1]) == ARGV[1]) then
    return redis.call('del', KEYS[1])
end
return 0