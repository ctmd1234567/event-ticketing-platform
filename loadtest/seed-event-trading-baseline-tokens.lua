local count=tonumber(ARGV[1])
if not count or count < 1 or count > 5000 then
  return redis.error_reply('count must be between 1 and 5000')
end

for index=1,count do
  local token=string.format('%032x',100000+index)
  local userId=9900071000+index
  local key='login:token:' .. token
  redis.call('HSET',key,'id',tostring(userId),'nickName','item7_baseline_' .. index)
  redis.call('EXPIRE',key,3600)
end
return count
