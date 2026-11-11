-- =====================================================================
-- consultation_message 表 —— 防重复唯一索引
-- 方案 A：数据库层硬兜底，比应用层先查后插更安全
-- 索引含义：同一会话里，同一个发送者的同一条消息（前 200 字相同）只能存一条
-- 使用前缀索引是因为 content 是 TEXT，MySQL 唯一索引有长度限制
-- 长度 200 足够覆盖绝大多数用户输入和 AI 回复的开头特征
-- =====================================================================

-- 先删除可能存在的同名索引（幂等）
DROP INDEX IF EXISTS uk_session_sender_content ON consultation_message;

-- 加唯一索引
ALTER TABLE consultation_message
  ADD UNIQUE INDEX uk_session_sender_content (session_id, sender_type, content(200));

-- 验证
SHOW INDEX FROM consultation_message WHERE Key_name = 'uk_session_sender_content';
