package org.dromara.db.alert.service;

import org.dromara.db.alert.domain.DbAlertEvent;

import java.util.Date;
import java.util.List;

public interface IAlertEventService {
    List<DbAlertEvent> list(String status, Long dataSourceId, int limit);
    DbAlertEvent acknowledge(Long id, Integer version);
    DbAlertEvent silence(Long id, Date until, String reason, Integer version);
}
