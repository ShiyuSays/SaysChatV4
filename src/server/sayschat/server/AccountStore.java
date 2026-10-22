package sayschat.server;

import sayschat.common.Crypto;
import sayschat.common.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账户存储：当前目录下 sayschat-accounts.dat（UTF-8 JSON）。
 * 保存用户名、随机盐、PBKDF2 密码哈希、管理员标记、封禁标记。
 * 账户密码明文绝不会写入磁盘。
 */
public final class AccountStore {
    public static final String FILE_NAME = "sayschat-accounts.dat";
    private static final int FORMAT_VERSION = 1;

    public static final class Account {
        public String name;
        public byte[] salt;
        public byte[] hash;
        public int iterations;
        public boolean admin;
        public boolean banned;

        Account copy() {
            Account a = new Account();
            a.name = name;
            a.salt = salt == null ? null : salt.clone();
            a.hash = hash == null ? null : hash.clone();
            a.iterations = iterations;
            a.admin = admin;
            a.banned = banned;
            return a;
        }
    }

    private final File file;
    private final Map<String, Account> map = new LinkedHashMap<String, Account>();

    public AccountStore(File dir) throws IOException {
        this.file = new File(dir, FILE_NAME);
        if (file.exists()) load();
    }

    /** 返回账户快照，避免调用方直接修改存储中的对象。 */
    public synchronized Account get(String name) {
        Account a = map.get(name);
        return a == null ? null : a.copy();
    }

    public synchronized Collection<Account> all() {
        Collection<Account> out = new ArrayList<Account>();
        for (Account a : map.values()) out.add(a.copy());
        return out;
    }

    public synchronized boolean hasAdmin() {
        for (Account a : map.values()) {
            if (a.admin) return true;
        }
        return false;
    }

    /** 注册新账户并立即落盘。 */
    public synchronized void register(String name, String password) throws IOException {
        if (map.containsKey(name)) throw new IOException("用户已存在");
        Account a = new Account();
        a.name = name;
        a.salt = Crypto.randomBytes(Crypto.SALT_BYTES);
        a.iterations = Crypto.PBKDF2_ITERATIONS;
        a.hash = Crypto.pbkdf2(password.toCharArray(), a.salt,
                a.iterations, Crypto.ACCOUNT_HASH_BYTES);
        a.admin = false;
        a.banned = false;
        map.put(name, a);
        save();
    }

    public synchronized void setAdmin(String name, boolean value) throws IOException {
        Account a = map.get(name);
        if (a == null) throw new IOException("用户不存在");
        a.admin = value;
        save();
    }

    public synchronized void setBanned(String name, boolean value) throws IOException {
        Account a = map.get(name);
        if (a == null) throw new IOException("用户不存在");
        a.banned = value;
        save();
    }

    /** 修改密码：生成新盐和新哈希；写盘失败时回滚内存中的账户。 */
    public synchronized void changePassword(String name, String password) throws IOException {
        Account a = map.get(name);
        if (a == null) throw new IOException("用户不存在");
        byte[] oldSalt = a.salt;
        byte[] oldHash = a.hash;
        int oldIterations = a.iterations;
        int iterations = Crypto.PBKDF2_ITERATIONS;
        byte[] salt = Crypto.randomBytes(Crypto.SALT_BYTES);
        byte[] hash = Crypto.pbkdf2(password.toCharArray(), salt,
                iterations, Crypto.ACCOUNT_HASH_BYTES);
        a.salt = salt;
        a.hash = hash;
        a.iterations = iterations;
        try {
            save();
        } catch (IOException e) {
            a.salt = oldSalt;
            a.hash = oldHash;
            a.iterations = oldIterations;
            throw e;
        }
    }

    /** 校验账户密码，使用恒定时间比较。 */
    public boolean checkPassword(String name, String password) {
        byte[] salt;
        byte[] expected;
        int iterations;
        synchronized (this) {
            Account a = map.get(name);
            if (a == null) return false;
            salt = a.salt.clone();
            expected = a.hash.clone();
            iterations = a.iterations;
        }
        if (salt.length == 0 || expected.length == 0) return false;
        byte[] actual = Crypto.pbkdf2(password.toCharArray(), salt,
                iterations, expected.length);
        boolean ok = Crypto.constantEquals(actual, expected);
        java.util.Arrays.fill(actual, (byte) 0);
        return ok;
    }

    private void load() throws IOException {
        byte[] data = readAll(file);
        Object rootObj = Json.parse(new String(data, "UTF-8"));
        if (!(rootObj instanceof Map)) throw new IOException("账户文件格式错误");
        Map<String, Object> root = Json.obj(rootObj);
        Object accountsObj = root.get("accounts");
        Map<String, Object> accounts;
        if (accountsObj instanceof Map) {
            accounts = Json.obj(accountsObj);
        } else {
            // 兼容更早版本的根节点直接映射用户名的格式。
            accounts = root;
        }
        for (Map.Entry<String, Object> entry : accounts.entrySet()) {
            if ("version".equals(entry.getKey())) continue;
            Object valueObj = entry.getValue();
            if (!(valueObj instanceof Map)) continue;
            Map<String, Object> m = Json.obj(valueObj);
            String saltHex = Json.str(m, "salt");
            String hashHex = Json.str(m, "hash");
            if (saltHex == null || hashHex == null) {
                throw new IOException("账户 " + entry.getKey() + " 数据不完整");
            }
            Account a = new Account();
            a.name = entry.getKey();
            a.salt = Crypto.unhex(saltHex);
            a.hash = Crypto.unhex(hashHex);
            Integer iterations = asInt(m.get("iterations"));
            if (iterations == null) {
                // 早期版本的账户文件使用 20 字节哈希和 10000 次迭代。
                iterations = a.hash.length == 20 ? 10000 : Crypto.PBKDF2_ITERATIONS;
            }
            if (iterations < Crypto.MIN_ITERATIONS || iterations > Crypto.MAX_ITERATIONS) {
                throw new IOException("账户 " + entry.getKey() + " 的迭代次数非法");
            }
            a.iterations = iterations;
            a.admin = Json.bool(m, "admin", false);
            a.banned = Json.bool(m, "banned", false);
            map.put(a.name, a);
        }
    }

    private static Integer asInt(Object o) {
        if (o instanceof Number) return Integer.valueOf(((Number) o).intValue());
        if (o instanceof String) {
            try {
                return Integer.valueOf(Integer.parseInt((String) o));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private synchronized void save() throws IOException {
        Map<String, Object> accounts = new LinkedHashMap<String, Object>();
        for (Account a : map.values()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("salt", Crypto.hex(a.salt));
            m.put("hash", Crypto.hex(a.hash));
            m.put("iterations", Integer.valueOf(a.iterations));
            m.put("admin", Boolean.valueOf(a.admin));
            m.put("banned", Boolean.valueOf(a.banned));
            accounts.put(a.name, m);
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("version", Integer.valueOf(FORMAT_VERSION));
        root.put("accounts", accounts);

        File tmp = new File(file.getAbsoluteFile().getParentFile(), FILE_NAME + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(Json.stringify(root).getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        ServerConfig.restrictPermissions(tmp);
        try {
            Files.move(tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        ServerConfig.restrictPermissions(file);
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
