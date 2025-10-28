package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private static final String LOCK_KEY_PREFIX = "lock:";
    private static final String ID_KEY_PREFIX = UUID.randomUUID().toString(true) + "-";

    private final StringRedisTemplate stringRedisTemplate;
    // 业务名字标识，与key前缀拼接
    private final String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识
        String threadId = ID_KEY_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY_PREFIX + name, threadId,
                timeoutSec, TimeUnit.SECONDS);
        // 这样返回结果,避免自动拆箱的空指针异常
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {

        // 调用lua脚本，实现判断锁标识与释放锁这两个动作的原子性
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(LOCK_KEY_PREFIX + name),
                ID_KEY_PREFIX + Thread.currentThread().getId()
        );

        /*// 获取当前线程标识
        String threadId = ID_KEY_PREFIX + Thread.currentThread().getId();
        // 获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(LOCK_KEY_PREFIX + name);
        // 判断标识是否一致，一致才能删除锁
        if (threadId.equals(id)) {
            stringRedisTemplate.delete(LOCK_KEY_PREFIX + name);
        }*/
    }
}
