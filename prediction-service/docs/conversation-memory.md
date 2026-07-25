# Agent 会话与长期记忆

启用 `memory` profile 后，会话管理使用 PostgreSQL 作为长期存储、Redis 作为短期上下文：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'memory'
$env:CHAT_MEMORY_DB_URL = 'jdbc:postgresql://localhost:5432/campus_water'
$env:CHAT_MEMORY_DB_USERNAME = 'postgres'
$env:CHAT_MEMORY_DB_PASSWORD = 'your-password'
```

启动时会执行 `db/chat-memory-schema-postgresql.sql`，创建 `agent_conversation` 和
`agent_message` 表。会话所有权通过 `X-User-Id` 传递；生产环境应由认证网关注入
真实用户 ID，不能信任客户端自行填写的 header。

## API

- `POST /ai/conversations`：创建会话。
- `GET /ai/conversations`：获取当前用户会话列表。
- `POST /ai/chatWithAgent?conversationId={id}&input={text}`：发送消息。未传
  `conversationId` 时自动创建会话，响应中的 `data.conversationId` 应由前端保存。
- `GET /ai/conversations/{id}/messages`：获取持久化聊天记录。
- `POST /ai/conversations/{id}/clear-context`：清除 Redis 上下文与摘要，保留审计消息。
- `DELETE /ai/conversations/{id}`：软删除会话并移除 Redis 上下文。

Redis 保存最近 20 条 LangChain4j 消息，默认 TTL 为 24 小时。每累计 12 条未摘要消息，
服务会调用模型生成不超过 400 字的长期摘要，保存到 PostgreSQL，并在下一轮请求中追加至
系统提示词。完整消息不会进入每次模型请求，避免上下文无限增长。
