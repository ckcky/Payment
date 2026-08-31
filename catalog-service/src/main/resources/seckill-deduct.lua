-- 秒杀配额原子预扣（014）
-- KEYS[1] = 秒杀配额 key（seckill:sku:{skuId}）
-- ARGV[1] = 预扣数量
-- 返回值：>=0 扣减后剩余；-1 配额不足；-2 未播种（非秒杀品，放行）
local exists = redis.call('EXISTS', KEYS[1])
if exists == 0 then
  return -2
end
local cur = tonumber(redis.call('GET', KEYS[1]))
local qty = tonumber(ARGV[1])
if cur < qty then
  return -1
end
return redis.call('DECRBY', KEYS[1], qty)
