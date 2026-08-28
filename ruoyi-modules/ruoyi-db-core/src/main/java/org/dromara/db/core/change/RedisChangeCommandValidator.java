package org.dromara.db.core.change;

import org.dromara.db.core.domain.RedisChangeCommand;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Redis 变更命令校验器（docs/06 §8.4、§8.3，M5-03）。
 *
 * <p>纯静态方法：白名单校验（SET/DEL/HSET/HDEL/EXPIRE）、逐 key 前缀鉴权（命中工单授权前缀）、
 * 禁止脚本/事务/管理命令（不在白名单即拒）、参数完整性。失败关闭：未知/禁用命令或越权 key 整批拒绝。</p>
 *
 * @author DataGate
 */
public final class RedisChangeCommandValidator {

    private RedisChangeCommandValidator() {
    }

    /** P0 受控写命令白名单（docs/06 §8.4） */
    public static final Set<String> WHITELIST = new HashSet<>(Arrays.asList(
        "SET", "DEL", "HSET", "HDEL", "EXPIRE"));

    /** 禁止命令（脚本/事务/管理，docs/06 §8.3） */
    public static final Set<String> FORBIDDEN = new HashSet<>(Arrays.asList(
        "EVAL", "EVALSHA", "FUNCTION", "SCRIPT", "MULTI", "EXEC", "WATCH",
        "FLUSHDB", "FLUSHALL", "MIGRATE", "RESTORE", "DUMP", "MODULE", "ACL",
        "COMMAND", "CLIENT", "CONFIG", "DEBUG", "SHUTDOWN", "MONITOR",
        "PSUBSCRIBE", "SUBSCRIBE", "BLPOP", "BRPOP", "BZPOPMAX", "BZPOPMIN",
        "XREAD", "KEYS", "BGREWRITEAOF", "BGSAVE", "SAVE", "SLAVEOF", "REPLICAOF"));

    /**
     * 校验单条命令。
     *
     * @param cmd              命令
     * @param authorizedPrefixes 工单授权 key 前缀集合（空表示不限制——仅测试用，生产必填）
     * @return 错误原因；null 表示通过
     */
    public static String validate(RedisChangeCommand cmd, List<String> authorizedPrefixes) {
        if (cmd == null || cmd.op() == null || cmd.key() == null || cmd.key().isEmpty()) {
            return "命令或 key 为空";
        }
        String op = cmd.op();
        if (FORBIDDEN.contains(op)) {
            return "禁止命令：" + op;
        }
        if (!WHITELIST.contains(op)) {
            return "非白名单命令：" + op;
        }
        // 参数完整性
        switch (op) {
            case "SET" -> { if (cmd.args().size() < 1) return op + " 参数不足（需 value）"; }
            case "HSET" -> { if (cmd.args().size() < 2) return op + " 参数不足（需 field+value）"; }
            case "EXPIRE", "HDEL" -> { if (cmd.args().size() < 1) return op + " 参数不足（需 seconds/field）"; }
            case "DEL" -> { /* 仅 key */ }
        }
        // 逐 key 前缀鉴权（docs/06 §8.4：每个 key 须命中工单授权范围）
        if (authorizedPrefixes != null && !authorizedPrefixes.isEmpty()) {
            if (!prefixMatch(cmd.key(), authorizedPrefixes)) {
                return "key 越权（未命中授权前缀）：" + maskKey(cmd.key());
            }
        }
        return null;
    }

    /**
     * 批量校验；任一失败→整批拒绝（返回首个错误）。
     */
    public static ValidationOutcome validateAll(List<RedisChangeCommand> cmds, List<String> authorizedPrefixes) {
        if (cmds == null || cmds.isEmpty()) {
            return new ValidationOutcome(false, "命令列表为空", List.of());
        }
        List<RedisChangeCommand> valid = new ArrayList<>();
        for (RedisChangeCommand c : cmds) {
            String err = validate(c, authorizedPrefixes);
            if (err != null) {
                return new ValidationOutcome(false, err, List.of());
            }
            valid.add(c);
        }
        return new ValidationOutcome(true, null, valid);
    }

    static boolean prefixMatch(String key, List<String> prefixes) {
        for (String p : prefixes) {
            if (p != null && key.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 遮蔽 key（不泄露完整 key 到错误/日志）。 */
    static String maskKey(String key) {
        if (key == null || key.length() <= 4) {
            return "****";
        }
        return key.substring(0, 2) + "****" + key.substring(key.length() - 2);
    }

    /**
     * 校验结果。
     *
     * @param valid   是否全部通过
     * @param error   首个错误原因（失败时）
     * @param commands 通过校验的命令（成功时）
     */
    public record ValidationOutcome(boolean valid, String error, List<RedisChangeCommand> commands) {
    }
}
