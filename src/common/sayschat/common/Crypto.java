package sayschat.common;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * SaysChatV4 加密工具。
 *
 * <p>连接密钥：由“连接密码 + 服务器随机盐”经 PBKDF2 派生。
 * 传输加密：AES-128-CBC + HMAC-SHA256（encrypt-then-MAC），带完整性校验。
 * 账户密码：PBKDF2 加盐哈希后保存，服务器从不保存账户密码明文。</p>
 *
 * <p>所有算法都是 Java 8 与 Android API 21+ 自带的实现，不依赖第三方库。</p>
 */
public final class Crypto {
    /** 握手与账户密码使用的 PBKDF2 迭代次数。 */
    public static final int PBKDF2_ITERATIONS = 120000;
    /** 握手时允许的迭代次数范围，防止恶意对端造成超大计算量。 */
    public static final int MIN_ITERATIONS = 10000;
    public static final int MAX_ITERATIONS = 1000000;

    public static final int SALT_BYTES = 16;
    public static final int MASTER_KEY_BYTES = 32;
    public static final int ACCOUNT_HASH_BYTES = 32;

    private static final SecureRandom RND = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Crypto() {
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RND.nextBytes(b);
        return b;
    }

    /** PBKDF2-HMAC-SHA1 派生密钥。 */
    public static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLenBytes) {
        if (password == null) throw new IllegalArgumentException("password == null");
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("非法 PBKDF2 迭代次数: " + iterations);
        }
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLenBytes * 8);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            return f.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("PBKDF2 初始化失败: " + e.getMessage(), e);
        } finally {
            spec.clearPassword();
        }
    }

    /** 账户密码哈希（32 字节）。 */
    public static byte[] hashPassword(String password, byte[] salt) {
        return pbkdf2(password.toCharArray(), salt, PBKDF2_ITERATIONS, ACCOUNT_HASH_BYTES);
    }

    /** 恒定时间比较，避免根据耗时猜测哈希。 */
    public static boolean constantEquals(byte[] a, byte[] b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a, b);
    }

    public static String hex(byte[] b) {
        if (b == null) return "";
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xff;
            c[i * 2] = HEX[v >>> 4];
            c[i * 2 + 1] = HEX[v & 0xf];
        }
        return new String(c);
    }

    public static byte[] unhex(String s) {
        if (s == null) throw new IllegalArgumentException("hex == null");
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("非法 hex 长度");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("非法 hex 字符");
            b[i] = (byte) ((hi << 4) | lo);
        }
        return b;
    }

    /**
     * 单条连接的会话加密器。
     * 帧格式：IV(16) + AES-CBC 密文 + HMAC-SHA256(32)。
     */
    public static final class Session {
        private final SecretKeySpec aesKey;
        private final SecretKeySpec macKey;

        /** @param masterKey 32 字节，前 16 字节用于 AES，后 16 字节用于 HMAC。 */
        public Session(byte[] masterKey) {
            if (masterKey == null || masterKey.length < MASTER_KEY_BYTES) {
                throw new IllegalArgumentException("会话密钥需要至少 32 字节");
            }
            aesKey = new SecretKeySpec(masterKey, 0, 16, "AES");
            macKey = new SecretKeySpec(masterKey, 16, 16, "HmacSHA256");
        }

        public byte[] encrypt(byte[] plain) throws GeneralSecurityException {
            byte[] iv = randomBytes(16);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
            byte[] ct = c.doFinal(plain);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(macKey);
            mac.update(iv);
            mac.update(ct);
            byte[] tag = mac.doFinal();
            byte[] out = new byte[16 + ct.length + 32];
            System.arraycopy(iv, 0, out, 0, 16);
            System.arraycopy(ct, 0, out, 16, ct.length);
            System.arraycopy(tag, 0, out, 16 + ct.length, 32);
            return out;
        }

        /** 解密并校验完整性；密钥错误或被篡改时返回 null。 */
        public byte[] decrypt(byte[] in) throws GeneralSecurityException {
            if (in == null || in.length < 48) return null;
            byte[] iv = new byte[16];
            byte[] tag = new byte[32];
            byte[] ct = new byte[in.length - 48];
            System.arraycopy(in, 0, iv, 0, 16);
            System.arraycopy(in, 16, ct, 0, ct.length);
            System.arraycopy(in, 16 + ct.length, tag, 0, 32);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(macKey);
            mac.update(iv);
            mac.update(ct);
            byte[] expect = mac.doFinal();
            if (!constantEquals(tag, expect)) return null;
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(iv));
            return c.doFinal(ct);
        }
    }
}
