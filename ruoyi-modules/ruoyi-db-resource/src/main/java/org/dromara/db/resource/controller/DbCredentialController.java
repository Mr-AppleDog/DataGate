package org.dromara.db.resource.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.db.core.enums.CredentialPurpose;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.resource.domain.bo.DbCredentialBo;
import org.dromara.db.resource.domain.vo.DbCredentialVo;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 凭据保险箱（CRED-001~007）。
 *
 * <p>铁律：密码只写不回显——不提供任何读取明文/密文的接口；
 * 列表只返回非秘密元信息；表单回填被禁止。</p>
 *
 * @author DataGate
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/db/credential")
public class DbCredentialController extends BaseController {

    private final ICredentialVaultService credentialVaultService;

    /**
     * 查询数据源的凭据元信息列表（仅非秘密字段）
     */
    @SaCheckPermission("db:credential:list")
    @GetMapping("/list/{dataSourceId}")
    public R<List<DbCredentialVo>> list(@PathVariable @NotNull Long dataSourceId) {
        return R.ok(credentialVaultService.listByDataSource(dataSourceId));
    }

    /**
     * 创建凭据（密码只写一次，信封加密后入库；请求体不得进入任何日志）
     */
    @SaCheckPermission("db:credential:add")
    @PostMapping
    public R<Long> add(@Validated @RequestBody DbCredentialBo bo) {
        try (SecretValue secret = SecretValue.of(bo.getPassword())) {
            Long id = credentialVaultService.createCredential(
                bo.getDataSourceId(), CredentialPurpose.valueOf(bo.getPurpose()),
                bo.getUsername(), secret);
            return R.ok(id);
        } finally {
            // 清除 BO 中的明文引用，减少驻留
            bo.setPassword(null);
        }
    }

    /**
     * 禁用凭据
     */
    @SaCheckPermission("db:credential:disable")
    @PutMapping("/{id}/disable")
    public R<Void> disable(@PathVariable @NotNull Long id) {
        return toAjax(credentialVaultService.disable(id));
    }
}
