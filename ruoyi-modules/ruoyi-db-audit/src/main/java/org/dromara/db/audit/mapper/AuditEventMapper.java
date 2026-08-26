package org.dromara.db.audit.mapper;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.audit.domain.AuditEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;

import java.util.List;

/**
 * 审计事件 Mapper。只提供插入与查询，不提供业务 update/delete（AUD-004）。
 *
 * @author DataGate
 */
@Mapper
public interface AuditEventMapper extends BaseMapperPlus<AuditEvent, AuditEvent> {

    /**
     * 对哈希链分片加事务级咨询锁，串行化同分片写入
     * （pg_advisory_xact_lock 返回 void，外层 count 仅为结果可映射，锁副作用不变）
     */
    @Select("SELECT count(pg_advisory_xact_lock(hashtext(#{chainKey})))")
    long lockChain(String chainKey);

    /**
     * 查询分片最新事件的哈希
     */
    @Select("SELECT event_hash FROM dbg_audit_event WHERE chain_key = #{chainKey} ORDER BY id DESC LIMIT 1")
    String selectLatestHash(@Param("chainKey") String chainKey);

    /**
     * 分片内全部事件（按雪花 ID 升序 = 追加顺序）。
     * 注意：仅供哈希链校验；flushCache=true 强制读数据库当前状态，
     * 否则同会话/事务内重复校验会命中 MyBatis 本地缓存而看不到最新行（篡改检测失效）。
     */
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Results(id = "auditChainResultMap", value = {
        @Result(column = "actor_snapshot", property = "actorSnapshot", typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER),
        @Result(column = "target_snapshot", property = "targetSnapshot", typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER),
        @Result(column = "details", property = "details", typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    })
    @Select("SELECT id, event_id, category, action, actor_id, actor_snapshot, target_type, target_id, "
        + "target_snapshot, result, source_ip, trace_id, details, occurred_at, previous_hash, event_hash "
        + "FROM dbg_audit_event WHERE chain_key = #{chainKey} ORDER BY id ASC")
    List<AuditEvent> selectByChainKey(@Param("chainKey") String chainKey);
}
