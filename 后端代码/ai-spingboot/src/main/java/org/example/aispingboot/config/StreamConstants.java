package org.example.aispingboot.config;

/**
 * Redis Stream 消息队列 —— 常量集中维护。
 *
 * <p>为什么要独立一个常量类？</p>
 * <ul>
 *   <li>Producer、Consumer、Config 三处都需要同一组 Key / Group 名；
 *   <li>避免魔法字符串散落，改 Key 名时只改一处；
 *   <li>Stream 的消费策略（Consumer Group 模式、ACK 语义）和这些常量强绑定，
 *       放在一起方便看整体设计。</li>
 * </ul>
 *
 * <h3>本次引入 Redis Stream 解决什么问题</h3>
 * 之前 AI SSE 流结束时，在 {@code doOnComplete} 里同步调用 MySQL 写库。
 * MySQL 写几百到一千字的 AI 回复需要几十毫秒，前端要多等这几十毫秒才能看到
 * "AI 回复完毕" —— 这几十毫秒对用户体验是可见的。
 * 引入 Stream 后：XADD 是微秒级的纯内存操作，写完立即 {@code sink.complete()} 关闭 SSE；
 * 消费者异步落库，用户无感知。
 *
 * <h3>Consumer Group 设计说明</h3>
 * <pre>
 * Stream:   ai:reply:stream
 * Group:    ai-reply-consumers        ← 固定，所有实例共享一个 Group
 * Consumer: consumer-{随机号}         ← 每实例启动时生成，支持多实例负载均衡
 * </pre>
 * 当一个 Consumer 挂掉（比如进程崩溃），Redis 会把它未 ACK 的消息放入 Pending Entries List，
 * 等存活的 Consumer 重平衡时重新领取，保证消息不丢。
 */
public final class StreamConstants {

    private StreamConstants() {}

    /** Stream Key —— AI 回复异步落库的主队列 */
    public static final String AI_REPLY_STREAM_KEY = "ai:reply:stream";

    /** Consumer Group 名 —— 所有消费实例共享此 Group */
    public static final String AI_REPLY_CONSUMER_GROUP = "ai-reply-consumers";

    // ---------- Stream 消息 Field 定义 ----------
    // Producer XADD 时的 field → value，Consumer onMessage 里按同名字段读取
    public static final String FIELD_DB_SESSION_ID = "dbSessionId";   // Long，数据库会话主键
    public static final String FIELD_CONTENT        = "content";       // String，AI 完整回复
    public static final String FIELD_AI_MODEL       = "aiModel";      // String，模型标识（"openai"/"deepseek"）

    /**
     * 积压阈值 —— 未来扩展用。
     * 当 Stream 长度超过此值时 Producer 自动降级为同步写库，
     * 防止消费者故障导致 Redis 内存爆涨。当前先不启用，预留常量位。
     */
    public static final long STREAM_BACKLOG_THRESHOLD = 1000L;
}
