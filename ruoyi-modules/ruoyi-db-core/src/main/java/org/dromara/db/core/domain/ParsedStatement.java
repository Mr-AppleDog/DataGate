package org.dromara.db.core.domain;

import org.dromara.db.core.enums.DbAction;

import java.util.List;

/**
 * 解析后的语句结构（QueryParser 输出）。
 * 解析失败必须失败关闭，不得交给数据库试运行。
 *
 * @param statementType       语句类型（SELECT/EXPLAIN/REDIS_READ 等）
 * @param resourcePaths       引用的规范化资源路径（鉴权输入，必须完整）
 * @param normalizedStatement 去常量后的归一化语句
 * @param fingerprint         方言指纹
 * @param requiredAction      该语句映射的资源动作
 * @param readonly            是否为安全只读语句
 * @author DataGate
 */
public record ParsedStatement(
    String statementType,
    List<String> resourcePaths,
    String normalizedStatement,
    String fingerprint,
    DbAction requiredAction,
    boolean readonly
) {

    public ParsedStatement {
        resourcePaths = resourcePaths == null ? List.of() : List.copyOf(resourcePaths);
    }
}
