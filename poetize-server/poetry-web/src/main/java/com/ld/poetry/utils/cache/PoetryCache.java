package com.ld.poetry.utils.cache;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Objects;


public class PoetryCache {

    //键值对集合
    private final static Map<String, Entity> map = new ConcurrentHashMap<>();

    //定时器线程池，用于清除过期缓存
    private final static ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "poetry-cache-expirer");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 添加缓存
     *
     * @param key  键
     * @param data 值
     */
    public static void put(String key, Object data) {
        put(key, data, 0);
    }

    /**
     * 添加缓存
     *
     * @param key    键
     * @param data   值
     * @param expire 过期时间，单位：秒， 0表示无限长
     */
    public static void put(String key, Object data, long expire) {
        Entity newEntity = new Entity(data, null);
        map.compute(key, (ignored, oldEntity) -> {
            if (oldEntity != null) {
                oldEntity.cancelExpiration();
            }
            return newEntity;
        });

        if (expire > 0) {
            scheduleExpiration(key, newEntity, expire);
        }
    }

    /**
     * 原子递增计数器；首次创建时设置固定过期时间，后续递增不延长窗口。
     */
    public static int increment(String key, long expire) {
        AtomicInteger result = new AtomicInteger();
        AtomicReference<Entity> created = new AtomicReference<>();
        map.compute(key, (ignored, oldEntity) -> {
            if (oldEntity != null && oldEntity.getValue() instanceof AtomicInteger counter) {
                result.set(counter.incrementAndGet());
                return oldEntity;
            }
            if (oldEntity != null) {
                oldEntity.cancelExpiration();
            }
            Entity newEntity = new Entity(new AtomicInteger(1), null);
            created.set(newEntity);
            result.set(1);
            return newEntity;
        });
        Entity newEntity = created.get();
        if (newEntity != null && expire > 0) {
            scheduleExpiration(key, newEntity, expire);
        }
        return result.get();
    }

    public static int getCount(String key) {
        Entity entity = map.get(key);
        if (entity != null && entity.getValue() instanceof AtomicInteger counter) {
            return counter.get();
        }
        return 0;
    }

    private static void scheduleExpiration(String key, Entity entity, long expire) {
        ScheduledFuture<?> future = executor.schedule(
                () -> map.remove(key, entity), expire, TimeUnit.SECONDS);
        entity.setFuture(future);
        if (map.get(key) != entity) {
            future.cancel(false);
        }
    }

    /**
     * 读取缓存
     *
     * @param key 键
     * @return
     */
    public static Object get(String key) {
        Entity entity = map.get(key);
        return entity == null ? null : entity.getValue();
    }

    /**
     * 读取所有缓存
     *
     * @return
     */
    public static Collection values() {
        return map.values();
    }

    /**
     * 清除缓存
     *
     * @param key
     * @return
     */
    public static Object remove(String key) {
        //清除原缓存数据
        Entity entity = map.remove(key);
        if (entity == null) return null;
        //清除原键值对定时器
        entity.cancelExpiration();
        return entity.getValue();
    }

    /**
     * 仅当缓存值与期望值相等时原子删除，适用于一次性验证码等消费场景。
     */
    public static boolean removeIfEquals(String key, Object expectedValue) {
        if (key == null || expectedValue == null) {
            return false;
        }
        AtomicReference<Entity> removed = new AtomicReference<>();
        map.computeIfPresent(key, (ignored, entity) -> {
            if (Objects.equals(entity.getValue(), expectedValue)) {
                removed.set(entity);
                return null;
            }
            return entity;
        });
        Entity entity = removed.get();
        if (entity == null) {
            return false;
        }
        entity.cancelExpiration();
        return true;
    }

    /**
     * 查询当前缓存的键值对数量
     *
     * @return
     */
    public static int size() {
        return map.size();
    }

    /**
     * 缓存实体类
     */
    private static class Entity {
        //键值对的value
        private Object value;

        //定时器Future
        private volatile ScheduledFuture<?> future;

        public Entity(Object value, ScheduledFuture<?> future) {
            this.value = value;
            this.future = future;
        }

        /**
         * 获取值
         *
         * @return
         */
        public Object getValue() {
            return value;
        }

        /**
         * 获取Future对象
         *
         * @return
         */
        public ScheduledFuture<?> getFuture() {
            return future;
        }

        public void setFuture(ScheduledFuture<?> future) {
            this.future = future;
        }

        public void cancelExpiration() {
            ScheduledFuture<?> expiration = future;
            if (expiration != null) {
                expiration.cancel(false);
            }
        }
    }
}
