package org.dromara.db.core.spi;

import org.dromara.db.core.domain.CollectorCursor;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.SlowQueryRecord;
import org.dromara.db.core.security.SecretValue;

import java.util.List;

/**
 * 慢查询采集提供者（docs/07 §4）。
 * 实现方负责增量游标、去重与重启/轮转/重置检测；记录必须先完成敏感字面量清理。
 * 连接配置与监控账号秘密由编排服务解析后注入（与 MetadataProvider.fetchCatalog 同构）。
 *
 * @author DataGate
 */
public interface SlowQueryProvider {

    /**
     * 增量拉取慢查询记录。
     *
     * @param profile 连接配置（非秘密）
     * @param secret  监控账号秘密（使用后由调用方销毁，不得进日志/异常）
     * @param cursor  上次游标（null 表示首次采集）
     * @param limit   单次拉取上限
     * @return 标准化记录与新游标
     */
    SlowQueryPage pull(ConnectionProfile profile, SecretValue secret, CollectorCursor cursor, int limit);

    /**
     * 一页慢查询记录
     *
     * @param records    记录列表（已归一化、双指纹、敏感清理）
     * @param nextCursor 下一游标
     */
    record SlowQueryPage(List<SlowQueryRecord> records, CollectorCursor nextCursor) {
    }
}
