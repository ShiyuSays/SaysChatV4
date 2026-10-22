package sayschat.server;

import sayschat.common.Crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Properties;
import java.util.Set;

/**
 * 服务器配置：首次启动在当前工作目录生成 sayschat-server.conf，
 * 内容包含监听端口（默认 10001）、客户端连接密码、最大同时在线人数、
 * 挂机踢出分钟数和同一 IP 的登录账户上限。
 * 之后每次启动读取该文件；修改后重启生效。
 */
public final class ServerConfig {
    public static final String FILE_NAME = "sayschat-server.conf";
    public static final int DEFAULT_PORT = 10001;
    public static final int DEFAULT_MAX_ONLINE = 100;
    public static final int MAX_ONLINE_LIMIT = 10000;
    public static final int DEFAULT_IDLE_MINUTES = 5;
    public static final int MAX_IDLE_MINUTES = 1440;
    public static final int DEFAULT_IP_ACCOUNT_LIMIT = 3;
    public static final int MAX_IP_ACCOUNT_LIMIT = 1000;
    private static final SecureRandom RND = new SecureRandom();

    public final int port;
    public final String password;
    /** 最大同时在线人数（已登录连接数）。 */
    public final int maxOnline;
    /** 连续多少分钟未发送任何消息自动踢出；0 表示关闭。 */
    public final int idleMinutes;
    /** 同一 IP 最多同时登录的账户数。 */
    public final int ipAccountLimit;

    public ServerConfig(int port, String password) {
        this(port, password, DEFAULT_MAX_ONLINE,
                DEFAULT_IDLE_MINUTES, DEFAULT_IP_ACCOUNT_LIMIT);
    }

    public ServerConfig(int port, String password, int maxOnline) {
        this(port, password, maxOnline, DEFAULT_IDLE_MINUTES, DEFAULT_IP_ACCOUNT_LIMIT);
    }

    public ServerConfig(int port, String password, int maxOnline,
                        int idleMinutes, int ipAccountLimit) {
        this.port = port;
        this.password = password;
        this.maxOnline = maxOnline;
        this.idleMinutes = idleMinutes;
        this.ipAccountLimit = ipAccountLimit;
    }

    /** 加载配置；文件不存在时生成随机连接密码并写入当前目录。 */
    public static ServerConfig load(File dir) throws IOException {
        File f = new File(dir, FILE_NAME);
        if (!f.exists()) {
            String pwd = randomPassword(20);
            ServerConfig cfg = new ServerConfig(DEFAULT_PORT, pwd, DEFAULT_MAX_ONLINE,
                    DEFAULT_IDLE_MINUTES, DEFAULT_IP_ACCOUNT_LIMIT);
            cfg.save(f);
            restrictPermissions(f);
            System.out.println("[配置] 首次启动，已生成 " + f.getAbsolutePath());
            System.out.println("[配置] 监听端口: " + cfg.port);
            System.out.println("[配置] 连接密码: " + pwd);
            System.out.println("[配置] 最大同时在线人数: " + cfg.maxOnline);
            System.out.println("[配置] 挂机踢出: " + cfg.idleMinutes + " 分钟未发言");
            System.out.println("[配置] 同一 IP 登录上限: " + cfg.ipAccountLimit + " 个账户");
            System.out.println("[配置] 请把连接密码安全地告知客户端用户；修改配置后重启生效。");
            return cfg;
        }

        Properties p = new Properties();
        Reader reader = new InputStreamReader(new FileInputStream(f), "UTF-8");
        try {
            p.load(reader);
        } finally {
            reader.close();
        }
        int port;
        try {
            port = Integer.parseInt(p.getProperty("port", String.valueOf(DEFAULT_PORT)).trim());
        } catch (NumberFormatException e) {
            throw new IOException(FILE_NAME + " 中的 port 不是有效数字");
        }
        if (port < 1 || port > 65535) {
            throw new IOException(FILE_NAME + " 中的 port 必须在 1-65535 之间");
        }
        String pwd = p.getProperty("password", "").trim();
        if (pwd.isEmpty()) {
            throw new IOException(FILE_NAME + " 缺少 password，请填写连接密码");
        }
        if (pwd.length() < 8) {
            System.out.println("[警告] 连接密码较短，建议使用至少 8 个字符的强密码。");
        }

        String maxOnlineText = p.getProperty("maxOnline");
        int maxOnline = DEFAULT_MAX_ONLINE;
        if (maxOnlineText != null) {
            try {
                maxOnline = Integer.parseInt(maxOnlineText.trim());
            } catch (NumberFormatException e) {
                throw new IOException(FILE_NAME + " 中的 maxOnline 不是有效数字");
            }
            if (maxOnline < 1 || maxOnline > MAX_ONLINE_LIMIT) {
                throw new IOException(FILE_NAME + " 中的 maxOnline 必须在 1-"
                        + MAX_ONLINE_LIMIT + " 之间");
            }
        } else {
            System.out.println("[配置] 未设置 maxOnline，使用默认值 "
                    + DEFAULT_MAX_ONLINE + "（可在 " + FILE_NAME + " 中添加）。");
        }

        String idleText = p.getProperty("idleMinutes");
        int idleMinutes = DEFAULT_IDLE_MINUTES;
        if (idleText != null) {
            try {
                idleMinutes = Integer.parseInt(idleText.trim());
            } catch (NumberFormatException e) {
                throw new IOException(FILE_NAME + " 中的 idleMinutes 不是有效数字");
            }
            if (idleMinutes < 0 || idleMinutes > MAX_IDLE_MINUTES) {
                throw new IOException(FILE_NAME + " 中的 idleMinutes 必须在 0-"
                        + MAX_IDLE_MINUTES + " 之间（0 表示关闭挂机踢出）");
            }
        } else {
            System.out.println("[配置] 未设置 idleMinutes，使用默认值 "
                    + DEFAULT_IDLE_MINUTES + " 分钟。");
        }

        String ipLimitText = p.getProperty("ipAccountLimit");
        int ipAccountLimit = DEFAULT_IP_ACCOUNT_LIMIT;
        if (ipLimitText != null) {
            try {
                ipAccountLimit = Integer.parseInt(ipLimitText.trim());
            } catch (NumberFormatException e) {
                throw new IOException(FILE_NAME + " 中的 ipAccountLimit 不是有效数字");
            }
            if (ipAccountLimit < 1 || ipAccountLimit > MAX_IP_ACCOUNT_LIMIT) {
                throw new IOException(FILE_NAME + " 中的 ipAccountLimit 必须在 1-"
                        + MAX_IP_ACCOUNT_LIMIT + " 之间");
            }
        } else {
            System.out.println("[配置] 未设置 ipAccountLimit，使用默认值 "
                    + DEFAULT_IP_ACCOUNT_LIMIT + " 个账户。");
        }

        if (idleText == null || ipLimitText == null) {
            appendMissingKeys(f, idleText == null, ipLimitText == null);
        }
        return new ServerConfig(port, pwd, maxOnline, idleMinutes, ipAccountLimit);
    }

    /** 为旧配置文件补充新增项；只追加缺失的键，不覆盖已有设置。 */
    private static void appendMissingKeys(File f, boolean idleMissing, boolean ipMissing) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        if (idleMissing) {
            sb.append("# 连续多少分钟未发送任何消息自动踢出，0 表示关闭\n");
            sb.append("idleMinutes=").append(DEFAULT_IDLE_MINUTES).append('\n');
        }
        if (ipMissing) {
            sb.append("# 同一 IP 最多同时登录的账户数\n");
            sb.append("ipAccountLimit=").append(DEFAULT_IP_ACCOUNT_LIMIT).append('\n');
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f, true);
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
            System.out.println("[配置] 已为旧配置文件补充新增配置项。");
        } catch (IOException e) {
            System.out.println("[配置] 无法补充配置文件，当前使用默认值: " + e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public void save(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# ============================================================\n");
        sb.append("# SaysChatV4.1.0 服务器配置文件（首次启动自动生成）\n");
        sb.append("# port      : 服务器监听端口，默认 10001\n");
        sb.append("# password  : 客户端连接密码，也是通信加密密钥的派生来源，请保密\n");
        sb.append("# maxOnline : 最大同时在线人数（1-").append(MAX_ONLINE_LIMIT).append("），默认 ")
                .append(DEFAULT_MAX_ONLINE).append("\n");
        sb.append("# idleMinutes : 连续多少分钟未发送消息自动踢出，0 表示关闭（0-")
                .append(MAX_IDLE_MINUTES).append("），默认 ")
                .append(DEFAULT_IDLE_MINUTES).append("\n");
        sb.append("# ipAccountLimit : 同一 IP 最多同时登录的账户数（1-")
                .append(MAX_IP_ACCOUNT_LIMIT).append("），默认 ")
                .append(DEFAULT_IP_ACCOUNT_LIMIT).append("\n");
        sb.append("# 修改后重启服务器生效。\n");
        sb.append("# ============================================================\n");
        sb.append("port=").append(port).append('\n');
        sb.append("password=").append(password).append('\n');
        sb.append("maxOnline=").append(maxOnline).append('\n');
        sb.append("idleMinutes=").append(idleMinutes).append('\n');
        sb.append("ipAccountLimit=").append(ipAccountLimit).append('\n');
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
        } finally {
            out.close();
        }
    }

    /** 生成随机连接密码，去掉容易混淆的 0/O/1/I/l 等字符。 */
    public static String randomPassword(int len) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(RND.nextInt(chars.length())));
        }
        return sb.toString();
    }

    static void restrictPermissions(File f) {
        try {
            Path path = Paths.get(f.getAbsolutePath());
            Set<PosixFilePermission> perms =
                    PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, perms);
        } catch (Exception ignored) {
            // Windows 等不支持 POSIX 权限的文件系统：忽略即可。
        }
    }
}
