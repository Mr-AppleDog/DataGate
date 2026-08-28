package org.dromara.db.alert.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.db.alert.domain.DbAlertEvent;
import org.dromara.db.alert.mapper.DbAlertEventMapper;
import org.dromara.db.alert.service.IAlertEventService;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class AlertEventServiceImpl implements IAlertEventService {
    private final DbAlertEventMapper eventMapper;
    public AlertEventServiceImpl(DbAlertEventMapper eventMapper) { this.eventMapper = eventMapper; }

    public List<DbAlertEvent> list(String status, Long dataSourceId, int limit) {
        LambdaQueryWrapper<DbAlertEvent> w = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) w.eq(DbAlertEvent::getStatus, status);
        if (dataSourceId != null) w.eq(DbAlertEvent::getDataSourceId, dataSourceId);
        w.orderByDesc(DbAlertEvent::getLastFiredAt).last("limit " + Math.max(1, Math.min(limit, 100)));
        return eventMapper.selectList(w);
    }
    public DbAlertEvent acknowledge(Long id, Integer version) {
        DbAlertEvent ev = loadOrThrow(id);
        if (version != null && ev.getVersion() != null && !version.equals(ev.getVersion()))
            throw new DbServiceException(DbErrorCode.WORKFLOW_STATE_CONFLICT, "事件版本已变化");
        ev.setStatus("ACKNOWLEDGED");
        int rows = eventMapper.updateById(ev);
        if (rows <= 0) throw new DbServiceException(DbErrorCode.WORKFLOW_STATE_CONFLICT, "确认失败，事件版本已变化");
        return eventMapper.selectById(id);
    }
    public DbAlertEvent silence(Long id, Date until, String reason, Integer version) {
        DbAlertEvent ev = loadOrThrow(id);
        if (version != null && ev.getVersion() != null && !version.equals(ev.getVersion()))
            throw new DbServiceException(DbErrorCode.WORKFLOW_STATE_CONFLICT, "事件版本已变化");
        ev.setStatus("SILENCED");
        ev.setSilenceUntil(until);
        ev.setEvidenceSummary(reason);
        int rows = eventMapper.updateById(ev);
        if (rows <= 0) throw new DbServiceException(DbErrorCode.WORKFLOW_STATE_CONFLICT, "静默失败，事件版本已变化");
        return eventMapper.selectById(id);
    }
    private DbAlertEvent loadOrThrow(Long id) {
        DbAlertEvent ev = eventMapper.selectById(id);
        if (ev == null) throw new DbServiceException(DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE, "告警事件不存在");
        return ev;
    }
}
