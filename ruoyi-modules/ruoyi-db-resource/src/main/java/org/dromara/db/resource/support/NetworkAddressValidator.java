package org.dromara.db.resource.support;

import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 网络地址校验器（SSRF 防护，docs/08 第 7 节，RES 安全前置）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>host 只允许字面量 IP 或可解析的 DNS 名（校验期解析并检查全部解析结果）；</li>
 *   <li>拒绝回环、链路本地、组播、未指定地址、云元数据地址（169.254.169.254）；</li>
 *   <li>协议/驱动由平台固定，用户不能提交任意 JDBC URL（结构化字段保证）；</li>
 *   <li>端口范围 1-65535。</li>
 * </ul>
 *
 * <p>注意：DNS 名在连接执行前必须重新解析复核（防 DNS rebinding），本类提供静态校验。</p>
 *
 * @author DataGate
 */
@Component
public class NetworkAddressValidator {

    private static final Pattern HOSTNAME_PATTERN =
        Pattern.compile("^(?=.{1,253}$)[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)*$");

    /**
     * 云元数据与特殊用途地址
     */
    private static final List<String> BLOCKED_EXACT = List.of(
        "169.254.169.254",
        "100.100.100.100"
    );

    /**
     * 校验结果
     *
     * @param allowed     是否允许
     * @param reason      拒绝原因（不含敏感信息）
     * @param resolvedIps 解析出的 IP（host 为域名时）
     */
    public record ValidationResult(boolean allowed, String reason, List<String> resolvedIps) {

        public static ValidationResult ok(List<String> resolvedIps) {
            return new ValidationResult(true, null, resolvedIps);
        }

        public static ValidationResult denied(String reason) {
            return new ValidationResult(false, reason, List.of());
        }
    }

    /**
     * 校验目标主机地址是否允许作为受管数据源接入
     *
     * @param host 主机（IP 或域名）
     * @param port 端口
     */
    public ValidationResult validate(String host, int port) {
        if (host == null || host.isBlank()) {
            return ValidationResult.denied("主机地址为空");
        }
        if (port < 1 || port > 65535) {
            return ValidationResult.denied("端口超出范围");
        }
        String trimmed = host.trim();

        // 字面量 IP
        try {
            InetAddress literal = InetAddress.getByName(trimmed);
            if (isLiteralIp(trimmed)) {
                return checkAddress(literal, trimmed);
            }
        } catch (Exception e) {
            return ValidationResult.denied("主机地址无法解析");
        }

        // 域名：先校验形态，再解析全部地址检查
        if (!HOSTNAME_PATTERN.matcher(trimmed).matches()) {
            return ValidationResult.denied("主机名格式非法");
        }
        try {
            InetAddress[] resolved = InetAddress.getAllByName(trimmed);
            if (resolved.length == 0) {
                return ValidationResult.denied("主机名无解析结果");
            }
            List<String> ips = new java.util.ArrayList<>();
            for (InetAddress addr : resolved) {
                ValidationResult r = checkAddress(addr, addr.getHostAddress());
                if (!r.allowed()) {
                    return ValidationResult.denied("主机解析到被禁止的地址");
                }
                ips.add(addr.getHostAddress());
            }
            return ValidationResult.ok(ips);
        } catch (Exception e) {
            return ValidationResult.denied("主机名解析失败");
        }
    }

    /**
     * 连接执行前的地址复核（防 DNS rebinding）：所有解析地址都必须通过检查
     */
    public boolean recheckResolved(String host) {
        try {
            for (InetAddress addr : InetAddress.getAllByName(host.trim())) {
                if (!isAllowedAddress(addr)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ValidationResult checkAddress(InetAddress addr, String display) {
        if (!isAllowedAddress(addr)) {
            return ValidationResult.denied("主机地址被网络策略拒绝: " + display);
        }
        return ValidationResult.ok(List.of(display));
    }

    private boolean isAllowedAddress(InetAddress addr) {
        if (BLOCKED_EXACT.contains(addr.getHostAddress())) {
            return false;
        }
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
            || addr.isMulticastAddress()) {
            return false;
        }
        // 链路本地（169.254.0.0/16、fe80::/10）
        if (addr instanceof Inet4Address v4) {
            int first = v4.getAddress()[0] & 0xFF;
            if (first == 169 && (v4.getAddress()[1] & 0xFF) == 254) {
                return false;
            }
            // CGNAT 100.64.0.0/10 默认不放行（云内网穿透地址段）
            if (first == 100 && ((v4.getAddress()[1] & 0xFF) >= 64 && (v4.getAddress()[1] & 0xFF) <= 127)) {
                return false;
            }
        }
        return true;
    }

    private boolean isLiteralIp(String host) {
        // IPv4 字面量或带括号的 IPv6 字面量
        if (host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            return true;
        }
        return host.contains(":");
    }
}
