package org.dromara.system.totp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * RFC 6238 TOTP 算法支持（HMAC-SHA1，30 秒步长，6 位码）。
 *
 * <p>不引入第三方依赖；密钥以 160bit 随机字节生成，Base32 编码对外展示（仅绑定时一次）。</p>
 *
 * @author DataGate
 */
public final class TotpSupport {

    private static final String HMAC_ALGO = "HmacSHA1";
    private static final int CODE_DIGITS = 6;
    private static final long STEP_SECONDS = 30L;
    private static final int SECRET_BYTES = 20;
    private static final int RECOVERY_CODE_COUNT = 8;
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private TotpSupport() {
    }

    /**
     * 生成新的 TOTP 密钥（原始字节）
     */
    public static byte[] generateSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /**
     * Base32 编码（无填充，Authenticator 兼容）
     */
    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(BASE32[(buffer >> (bitsLeft - 5)) & 0x1F]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32[(buffer << (5 - bitsLeft)) & 0x1F]);
        }
        return sb.toString();
    }

    /**
     * 计算指定时间步的 TOTP 码
     */
    public static String codeAtStep(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret, HMAC_ALGO));
            byte[] counter = new byte[8];
            for (int i = 7; i >= 0; i--) {
                counter[i] = (byte) (step & 0xFF);
                step >>= 8;
            }
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP 计算失败", e);
        }
    }

    public static long currentStep() {
        return System.currentTimeMillis() / 1000 / STEP_SECONDS;
    }

    /**
     * 校验验证码（窗口 ±1 步）。返回匹配的时间步，未匹配返回 null。
     */
    public static Long verify(byte[] secret, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        long step = currentStep();
        for (long offset = -1; offset <= 1; offset++) {
            if (codeAtStep(secret, step + offset).equals(normalized)) {
                return step + offset;
            }
        }
        return null;
    }

    /**
     * 生成恢复码（形如 XXXX-XXXX），返回明文（仅展示一次）
     */
    public static String[] generateRecoveryCodes() {
        String[] codes = new String[RECOVERY_CODE_COUNT];
        for (int i = 0; i < codes.length; i++) {
            StringBuilder sb = new StringBuilder(9);
            for (int j = 0; j < 8; j++) {
                if (j == 4) {
                    sb.append('-');
                }
                sb.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
            codes[i] = sb.toString();
        }
        return codes;
    }

    /**
     * 恢复码哈希（SHA-256，统一大写去空格横杠后计算）
     */
    public static String recoveryHash(String code) {
        try {
            String normalized = code == null ? "" : code.replace("-", "").trim().toUpperCase();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("恢复码哈希失败", e);
        }
    }
}
