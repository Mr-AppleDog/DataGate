package org.dromara.db.core.security;

import java.util.Arrays;

/**
 * 秘密值对象（docs/02 第 7.3 节、docs/11 第 8 节）。
 *
 * <p>用于承载数据库密码、Token、AccessKey Secret 等敏感值。约束：</p>
 * <ul>
 *   <li>{@link #toString()} 永远返回固定掩码，绝不输出真值；</li>
 *   <li>不提供 equals/hashCode，避免秘密被放入集合键或日志比较；</li>
 *   <li>使用完毕调用 {@link #destroy()} 清空底层数组；</li>
 *   <li>禁止序列化进 JSON / 日志 / 缓存。</li>
 * </ul>
 *
 * @author DataGate
 */
public final class SecretValue implements AutoCloseable {

    /**
     * 固定掩码，任何实例的 toString 返回值相同
     */
    public static final String MASK = "******";

    private char[] value;

    private SecretValue(char[] value) {
        this.value = value;
    }

    /**
     * 创建秘密值。调用方传入的数组不会被额外复制引用，构造后由本对象负责销毁。
     */
    public static SecretValue of(char[] value) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("secret value must not be empty");
        }
        return new SecretValue(value);
    }

    /**
     * 创建秘密值。尽量使用 {@link #of(char[])} 以减少不可变 String 驻留。
     */
    public static SecretValue of(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("secret value must not be empty");
        }
        return new SecretValue(value.toCharArray());
    }

    /**
     * 判断秘密是否已被销毁
     */
    public boolean isDestroyed() {
        return value == null;
    }

    /**
     * 在回调中使用秘密明文，回调返回后调用方应尽快销毁本对象。
     * 实现方不得把明文存入字段、日志或抛出异常的 message。
     *
     * @param consumer 明文消费者（如设置到 JDBC Properties）
     */
    public void useSecret(SecretConsumer consumer) {
        if (value == null) {
            throw new IllegalStateException("secret value has been destroyed");
        }
        consumer.accept(value);
    }

    /**
     * 清空底层秘密数组。清空后本对象不可再使用。
     */
    public void destroy() {
        if (value != null) {
            Arrays.fill(value, '\0');
            value = null;
        }
    }

    @Override
    public void close() {
        destroy();
    }

    /**
     * 永远返回固定掩码
     */
    @Override
    public String toString() {
        return MASK;
    }

    /**
     * 秘密明文消费回调
     */
    @FunctionalInterface
    public interface SecretConsumer {

        /**
         * 消费明文，禁止记录日志或持久化
         *
         * @param secret 明文字符数组
         */
        void accept(char[] secret);
    }
}
