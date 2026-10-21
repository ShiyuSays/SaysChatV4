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
 * 内容包含监听端口（默认 10001）、客户端连接密码和最大同时在线人数。
 * 之后每次启动读取该文件；修改后重启生效。
 */
public final class ServerConfig {
    public static final String FILE_NAME = "sayschat-server.conf";
    public static final int DEFAULT_PORT = 10001;
    public static final int DEFAULT_MAX_ONLINE = 100;
    public static final int MAX_ONLINE_LIMIT = 10000;
    private static final SecureRandom RND = new SecureRandom();

    public final int port;
    public final String password;
    /** 最大同时在线人数（已登录连接数）。 */
    public final int maxOnline;

    public ServerConfig(int port, String password) {
        this(port, password, DEFAULT_MAX_ONLINE);
    }

    public ServerConfig(int port, String password, int maxOnline) {
        this.port = port;
        this.password = password;
        this.maxOnline = maxOnline;
    }

    /** 加载配置；文件不存在时生成随机连接密码并写入当前目录。 */
    public static ServerConfig load(File dir) throws IOException {
        File f = new File(dir, FILE_NAME);
        if (!f.exists()) {
            String pwd = randomPassword(20);
            ServerConfig cfg = new ServerConfig(DEFAULT_PORT, pwd, DEFAULT_MAX_ONLINE);
            cfg.save(f);
            restrictPermissions(f);
            System.out.println("[配置] 首次启动，已生成 " + f.getAbsolutePath());
            System.out.println("[配置] 监听端口: " + cfg.port);
            System.out.println("[配置] 连接密码: " + pwd);
            System.out.println("[配置] 最大同时在线人数: " + cfg.maxOnline);
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
        return new ServerConfig(port, pwd, maxOnline);
    }

    public void save(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# ============================================================\n");
        sb.append("# SaysChatV4 服务器配置文件（首次启动自动生成）\n");
        sb.append("# port      : 服务器监听端口，默认 10001\n");
        sb.append("# password  : 客户端连接密码，也是通信加密密钥的派生来源，请保密\n");
        sb.append("# maxOnline : 最大同时在线人数（1-").append(MAX_ONLINE_LIMIT).append("），默认 ")
                .append(DEFAULT_MAX_ONLINE).append("\n");
        sb.append("# 修改后重启服务器生效。\n");
        sb.append("# ============================================================\n");
        sb.append("port=").append(port).append('\n');
        sb.append("password=").append(password).append('\n');
        sb.append("maxOnline=").append(maxOnline).append('\n');
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
