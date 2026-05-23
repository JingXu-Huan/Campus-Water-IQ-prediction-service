# Redis 缓存实现设计

## 目标

为 `IoTDataServiceImpl` 中 7 个高频查询方法添加 Redis 缓存，减少 InfluxDB 查询压力。

## 待缓存方法

| 方法 | 缓存键格式 | TTL | 数据类型 |
|------|-----------|-----|---------|
| `getFlowNow` | `flow:now:{deviceId}` | 5秒 | Double |
| `waterTrendsForTheWeek` | `water:trends:{campus}` | 60秒 | JSON字符串 |
| `getTurbidity` | `water:turbidity:{deviceId}` | 5秒 | Double |
| `getPh` | `water:ph:{deviceId}` | 5秒 | Double |
| `getChlorine` | `water:chlorine:{deviceId}` | 5秒 | Double |
| `getPressureNow` | `water:pressure:{deviceId}` | 5秒 | Double |
| `getTemNow` | `water:tem:{deviceId}` | 5秒 | Double |

## 缓存模式

```
Cache-Aside（旁路缓存）:
1. 先查 Redis
2. 命中则返回
3. 未命中则查 InfluxDB，结果写入 Redis 并返回
```

## 序列化方案

- Double 类型：直接用 `set(key, String.valueOf(value))`，读取时 `Double.parseDouble()`
- List 类型：用 `ObjectMapper` 序列化为 JSON 字符串存储

## 实现方式

每个方法结构：

```java
@Override
public Result<Double> getFlowNow(String deviceId) {
    String cacheKey = "flow:now:" + deviceId;
    String cached = redisTemplate.opsForValue().get(cacheKey);
    if (cached != null) {
        return Result.ok(Double.parseDouble(cached));
    }
    // 原有 InfluxDB 查询逻辑
    // ...
    redisTemplate.opsForValue().set(cacheKey, String.valueOf(flow), 5, TimeUnit.SECONDS);
    return Result.ok(flow);
}
```

## 注意事项

- TTL 从 1~5 秒不等，防止数据过期
- 不改变原有返回结构，只加缓存层
- 异常时降级：缓存失败不影响主流程