package org.dromara.db.auth.support;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

/**
 * IPv4 CIDR 匹配（docs/03 第 6 节 sourceIpCidr 条件，AUTH）。
 *
 * <p>仅支持 IPv4（IPv6 留待后续切片）。解析失败按不匹配处理（失败关闭）。</p>
 *
 * @author DataGate
 */
public final class CidrMatcher {

    private CidrMatcher() {
    }

    /**
     * 判断 ip 是否落在 cidr（如 10.0.0.0/8）内。
     *
     * @param ip  IPv4 字符串
     * @param cidr CIDR，如 "10.0.0.0/8"；无掩码长度则按精确 IP 匹配
     * @return true=落在网段内；ip/cidr 非法或版本不符返回 false
     */
    public static boolean matches(String ip, String cidr) {
        if (ip == null || cidr == null || cidr.isBlank()) {
            return false;
        }
        String trimmed = cidr.trim();
        int slash = trimmed.indexOf('/');
        String baseIp = slash < 0 ? trimmed : trimmed.substring(0, slash);
        int prefix = slash < 0 ? 32 : parsePrefix(trimmed.substring(slash + 1));
        if (prefix < 0 || prefix > 32) {
            return false;
        }
        Long ipInt = toIpv4Long(ip);
        Long baseInt = toIpv4Long(baseIp);
        if (ipInt == null || baseInt == null) {
            return false;
        }
        if (prefix == 0) {
            return true;
        }
        long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return (ipInt & mask) == (baseInt & mask);
    }

    private static int parsePrefix(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Long toIpv4Long(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            byte[] b = addr.getAddress();
            if (b.length != 4) {
                return null;
            }
            return ByteBuffer.wrap(b).getInt() & 0xFFFFFFFFL;
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
