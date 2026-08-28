package org.dromara.db.alert.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.db.alert.domain.DbAlertEvent;
import org.dromara.db.alert.service.IAlertEventService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/db/alert-events")
public class AlertEventController extends BaseController {
    private final IAlertEventService eventService;
    @SaCheckPermission("db:alert:event:list") @GetMapping
    public R<List<DbAlertEvent>> list(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) Long dataSourceId,
                                      @RequestParam(defaultValue = "50") int limit) {
        return R.ok(eventService.list(status, dataSourceId, limit));
    }
    @SaCheckPermission("db:alert:event:ack") @PostMapping("/{id}/acknowledge")
    public R<DbAlertEvent> acknowledge(@PathVariable Long id, @RequestParam Integer version) {
        return R.ok(eventService.acknowledge(id, version));
    }
    @SaCheckPermission("db:alert:event:silence") @PostMapping("/{id}/silence")
    public R<DbAlertEvent> silence(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        long untilMs = Long.parseLong(String.valueOf(body.getOrDefault("until", "0")));
        String reason = String.valueOf(body.getOrDefault("reason", ""));
        return R.ok(eventService.silence(id, untilMs > 0 ? new Date(untilMs) : null, reason, body.get("version") == null ? null : Integer.parseInt(String.valueOf(body.get("version")))));
    }
}
