package org.dromara.db.core.domain;

import java.util.List;

/**
 * Redis 变更工单单条命令（docs/06 §8.4，M5-03）。
 *
 * <p>结构化参数派发，不接受原始文本拼接（防命令注入）。P0 白名单：SET/DEL/HSET/HDEL/EXPIRE。
 *
 * @param op   命令动词（SET/DEL/HSET/HDEL/EXPIRE）
 * @param key  目标 key（必填，须命中工单授权前缀）
 * @param args 附加参数（SET:value; EXPIRE:seconds; HSET:field,value; HDEL:field）
 * @author DataGate
 */
public record RedisChangeCommand(String op, String key, List<String> args) {

    public RedisChangeCommand {
        if (op != null) op = op.toUpperCase();
        args = args == null ? List.of() : List.copyOf(args);
    }
}
