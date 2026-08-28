package org.dromara.db.connector.redis.support;

import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.domain.RowHeader;

import java.util.List;

/**
 * Redis 命令执行结果（执行器与命令派发器之间的纯数据契约，docs/06 §8、§11）。
 *
 * <p>命令派发器（Lettuce 实现/测试桩）将 RESP 响应塑形为统一列头 + 行集，
 * 由执行器施以行/字节上限与流式吐行。不承载原始 RESP 字节，便于审计只记规模。</p>
 *
 * @param header    列头
 * @param rows      行集（每行单元格列表，顺序与 header 一致）
 * @param truncated 派发器是否因硬上限截断
 * @author DataGate
 */
public record RedisResponse(RowHeader header, List<List<RowCell>> rows, boolean truncated) {

    public RedisResponse {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
