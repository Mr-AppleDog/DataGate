package org.dromara.db.observability.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 慢查询指纹与治理状态（docs/04 §7.3 + docs/07 §5.1 双指纹）。
 * 治理状态机作用于本表 governance_status（docs/05 §4.6），version 为治理乐观锁。
 *
 * @author DataGate
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dbg_slow_fingerprint")
public class DbSlowFingerprint extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long dataSourceId;

    private String databaseName;

    /**
     * MYSQL/POSTGRESQL/REDIS/TAIR
     */
    private String engine;

    /**
     * portableFingerprint（引擎无关 SHA-256）
     */
    private String fingerprint;

    /**
     * 引擎原生指纹（MySQL digest / PG queryid），可空
     */
    private String nativeFingerprint;

    private String parserVersion;

    /**
     * 归一化模板（去常量/脱敏，默认展示）
     */
    private String normalizedStatement;

    /**
     * 全表扫描/无 WHERE/无界分页等标记（JSON）
     */
    private String riskFlags;

    /**
     * DISCOVERED/CLAIMED/IN_PROGRESS/PENDING_VERIFY/RESOLVED/IGNORED
     */
    private String governanceStatus;

    private Long assigneeId;

    private Date firstSeenAt;

    private Date lastSeenAt;

    /**
     * 治理状态乐观锁（状态迁移携 version）
     */
    @Version
    private Integer version;

    @TableLogic
    private String delFlag;
}
