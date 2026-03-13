-- rollback seckill redis operations
-- ARGV[1]: voucherId
-- ARGV[2]: userId

local voucherId = ARGV[1]
local userId = ARGV[2]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- only rollback if user had been marked as ordered
if (redis.call('sismember', orderKey, userId) == 1) then
    redis.call('srem', orderKey, userId)
    redis.call('incrby', stockKey, 1)
end

return 0
