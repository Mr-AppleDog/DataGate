package org.dromara.db.core.change;

import org.dromara.db.core.domain.RedisChangeCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 变更命令校验单元测试（docs/06 §8.4、§8.3，M5-03）。
 *
 * @author DataGate
 */
@Tag("unit")
class RedisChangeCommandValidatorTest {

    private static final List<String> PREFIXES = List.of("user:", "session:");

    @Test
    void whitelist_commands_pass() {
        assertNull(RedisChangeCommandValidator.validate(
            new RedisChangeCommand("SET", "user:1", List.of("v1")), PREFIXES));
        assertNull(RedisChangeCommandValidator.validate(
            new RedisChangeCommand("DEL", "session:abc", List.of()), PREFIXES));
        assertNull(RedisChangeCommandValidator.validate(
            new RedisChangeCommand("EXPIRE", "user:1", List.of("360")), PREFIXES));
        assertNull(RedisChangeCommandValidator.validate(
            new RedisChangeCommand("HSET", "user:1", List.of("name", "alice")), PREFIXES));
        assertNull(RedisChangeCommandValidator.validate(
            new RedisChangeCommand("HDEL", "user:1", List.of("name")), PREFIXES));
    }

    @Test
    void forbidden_commands_rejected() {
        RedisChangeCommand eval = new RedisChangeCommand("EVAL", "user:1", List.of("return 1"));
        String err = RedisChangeCommandValidator.validate(eval, PREFIXES);
        assertTrue(err.contains("禁止命令"));
        assertTrue(RedisChangeCommandValidator.validate(
            new RedisChangeCommand("FLUSHDB", "x", List.of()), PREFIXES).contains("禁止"));
        assertTrue(RedisChangeCommandValidator.validate(
            new RedisChangeCommand("MULTI", "x", List.of()), PREFIXES).contains("禁止"));
    }

    @Test
    void non_whitelist_rejected() {
        String err = RedisChangeCommandValidator.validate(
            new RedisChangeCommand("GET", "user:1", List.of()), PREFIXES);
        assertTrue(err.contains("非白名单"));
    }

    @Test
    void key_outside_prefix_rejected() {
        String err = RedisChangeCommandValidator.validate(
            new RedisChangeCommand("SET", "order:1", List.of("v")), PREFIXES);
        assertTrue(err.contains("越权"));
        assertTrue(err.contains("****"), "key should be masked: " + err);
    }

    @Test
    void key_masked_in_error() {
        String err = RedisChangeCommandValidator.validate(
            new RedisChangeCommand("SET", "order:longkey", List.of("v")), PREFIXES);
        assertFalse(err.contains("order:longkey"), "full key must not leak: " + err);
    }

    @Test
    void insufficient_args_rejected() {
        String err = RedisChangeCommandValidator.validate(
            new RedisChangeCommand("SET", "user:1", List.of()), PREFIXES);
        assertTrue(err.contains("参数不足"));
        String err2 = RedisChangeCommandValidator.validate(
            new RedisChangeCommand("EXPIRE", "user:1", List.of()), PREFIXES);
        assertTrue(err2.contains("参数不足"));
    }

    @Test
    void empty_command_rejected() {
        String err = RedisChangeCommandValidator.validate(null, PREFIXES);
        assertTrue(err.contains("为空"));
    }

    @Test
    void validate_all_rejects_on_first_failure() {
        RedisChangeCommandValidator.ValidationOutcome out = RedisChangeCommandValidator.validateAll(
            List.of(new RedisChangeCommand("SET", "user:1", List.of("v")),
                    new RedisChangeCommand("EVAL", "user:2", List.of("x"))),
            PREFIXES);
        assertFalse(out.valid());
        assertTrue(out.error().contains("禁止"));
    }

    @Test
    void validate_all_passes_valid_batch() {
        RedisChangeCommandValidator.ValidationOutcome out = RedisChangeCommandValidator.validateAll(
            List.of(new RedisChangeCommand("SET", "user:1", List.of("v1")),
                    new RedisChangeCommand("DEL", "session:2", List.of())),
            PREFIXES);
        assertTrue(out.valid());
        assertEquals(2, out.commands().size());
    }

    @Test
    void empty_batch_rejected() {
        RedisChangeCommandValidator.ValidationOutcome out = RedisChangeCommandValidator.validateAll(null, PREFIXES);
        assertFalse(out.valid());
    }
}
