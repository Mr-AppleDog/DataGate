package org.dromara.db.alert.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.db.alert.domain.DbNotificationChannel;
import org.dromara.db.alert.notify.DeliveryResult;
import org.dromara.db.alert.service.INotificationChannelService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/db/notification-channels")
public class NotificationChannelController extends BaseController {
    private final INotificationChannelService channelService;
    @SaCheckPermission("db:alert:channel:list") @GetMapping
    public R<List<DbNotificationChannel>> list() { return R.ok(channelService.list()); }
    @SaCheckPermission("db:alert:channel:add") @PostMapping
    public R<DbNotificationChannel> create(@RequestBody DbNotificationChannel channel) { return R.ok(channelService.create(channel)); }
    @SaCheckPermission("db:alert:channel:edit") @PutMapping
    public R<DbNotificationChannel> update(@RequestBody DbNotificationChannel channel) { return R.ok(channelService.update(channel)); }
    @SaCheckPermission("db:alert:channel:test") @PostMapping("/{id}/test")
    public R<DeliveryResult> test(@PathVariable Long id) { return R.ok(channelService.test(id)); }
}
