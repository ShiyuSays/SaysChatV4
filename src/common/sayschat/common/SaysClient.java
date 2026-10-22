package sayschat.common;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用客户端连接层（TCP + 加密握手 + 帧协议），桌面端与安卓端共用。
 *
 * <p>握手流程：</p>
 * <ol>
 *   <li>服务器发送 25 字节明文头：协议标记 "SAYS4" + 随机盐(16) + PBKDF2 迭代次数(4)；</li>
 *   <li>双方用连接密码和盐派生 32 字节会话密钥；</li>
 *   <li>之后所有数据帧均为 AES-CBC + HMAC-SHA256 加密；</li>
 *   <li>连接密码错误时服务器返回明文 "BADPASS" 后断开。</li>
 * </ol>
 *
 * <p>回调运行在网络读线程，Swing / Android UI 必须自行切回界面线程。</p>
 */
public final class SaysClient {
    public static final String PROTOCOL_MAGIC = "SAYS4";
    public static final String BAD_PASSWORD_FRAME = "BADPASS";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int LOGIN_TIMEOUT_MS = 30000;

    /** 认证失败（账户密码错、用户名不存在、被封禁等），消息可直接展示给用户。 */
    public static final class AuthFailed extends Exception {
        private static final long serialVersionUID = 1L;

        public AuthFailed(String reason) {
            super(reason);
        }
    }

    /** 服务端消息回调，均在读线程调用。 */
    public interface Callback {
        void onMessage(Map<String, Object> m);

        void onDisconnected(String reason);
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Crypto.Session session;
    private volatile boolean closed;
    private volatile boolean readerStarted;

    /** 登录成功后的用户名。 */
    public volatile String user;
    /** 当前管理员状态；服务器可通过 role 消息实时变更。 */
    public volatile boolean admin;
    /** 当前是否处于全员禁言。 */
    public volatile boolean allMuted;
    /** 登录时服务器下发的在线用户快照（每项为 name/admin 的对象）。 */
    public volatile List<Object> users = new ArrayList<Object>();

    public SaysClient(String host, int port, String connectionPassword) throws IOException {
        if (host == null || host.trim().isEmpty()) throw new IOException("服务器地址不能为空");
        if (port < 1 || port > 65535) throw new IOException("端口必须在 1-65535 之间");
        if (connectionPassword == null || connectionPassword.isEmpty()) {
            throw new IOException("连接密码不能为空");
        }
        socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            try {
                // 移动网络下尽量保持连接，减少 NAT 空闲回收造成的断线。
                socket.setKeepAlive(true);
            } catch (IOException ignored) {
            }
            socket.connect(new InetSocketAddress(host.trim(), port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(LOGIN_TIMEOUT_MS);
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());
            session = handshake(connectionPassword);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private Crypto.Session handshake(String connectionPassword) throws IOException {
        byte[] hello = new byte[25];
        readFully(hello);
        for (int i = 0; i < PROTOCOL_MAGIC.length(); i++) {
            if (hello[i] != (byte) PROTOCOL_MAGIC.charAt(i)) {
                throw new IOException("目标不是 SaysChatV4.1.0 服务器");
            }
        }
        byte[] salt = new byte[16];
        System.arraycopy(hello, 5, salt, 0, 16);
        int iterations = ((hello[21] & 0xff) << 24) | ((hello[22] & 0xff) << 16)
                | ((hello[23] & 0xff) << 8) | (hello[24] & 0xff);
        if (iterations < Crypto.MIN_ITERATIONS || iterations > Crypto.MAX_ITERATIONS) {
            throw new IOException("服务器握手参数非法");
        }
        byte[] master = Crypto.pbkdf2(connectionPassword.toCharArray(), salt,
                iterations, Crypto.MASTER_KEY_BYTES);
        return new Crypto.Session(master);
    }

    /**
     * 登录或注册。注册成功后服务器会直接让该账户登录。
     *
     * @throws AuthFailed 账户层面的失败（可展示原因）
     * @throws IOException 网络或协议层面失败
     */
    public void login(String user, String password, boolean register, String device)
            throws IOException, AuthFailed {
        if (user == null || user.trim().isEmpty()) throw new AuthFailed("用户名不能为空");
        if (password == null || password.isEmpty()) throw new AuthFailed("账户密码不能为空");
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", "login");
        m.put("user", user.trim());
        m.put("pass", password);
        m.put("register", register);
        m.put("device", device == null ? "unknown" : device);
        send(m);

        byte[] frame = FrameIO.readFrame(in);
        if (frame == null) throw new IOException("服务器已断开连接");
        byte[] plain;
        try {
            plain = session.decrypt(frame);
        } catch (GeneralSecurityException e) {
            throw new IOException("服务器响应解密失败");
        }
        if (plain == null) {
            if (BAD_PASSWORD_FRAME.equals(new String(frame, "UTF-8"))) {
                throw new AuthFailed("连接密码错误");
            }
            throw new IOException("服务器响应不合法或已损坏");
        }
        Object parsed = Json.parse(new String(plain, "UTF-8"));
        if (!(parsed instanceof Map)) throw new IOException("服务器响应格式错误");
        Map<String, Object> resp = Json.obj(parsed);
        if (!Json.bool(resp, "ok", false)) {
            String reason = Json.str(resp, "reason");
            throw new AuthFailed(reason == null || reason.isEmpty() ? "登录失败" : reason);
        }
        this.user = Json.str(resp, "user");
        this.admin = Json.bool(resp, "admin", false);
        this.allMuted = Json.bool(resp, "allMuted", false);
        List<Object> snapshot = new ArrayList<Object>();
        Object ul = resp.get("users");
        if (ul instanceof List) {
            snapshot.addAll((List<?>) ul);
        }
        this.users = snapshot;
        socket.setSoTimeout(0);
    }

    /** 启动唯一读线程，持续接收服务器消息。 */
    public void startReader(final Callback cb) {
        if (readerStarted) throw new IllegalStateException("reader already started");
        readerStarted = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                String reason = "与服务器的连接已断开";
                try {
                    while (!closed) {
                        byte[] frame = FrameIO.readFrame(in);
                        if (frame == null) {
                            reason = "服务器已关闭连接";
                            break;
                        }
                        byte[] plain;
                        try {
                            plain = session.decrypt(frame);
                        } catch (GeneralSecurityException e) {
                            reason = "数据解密失败，连接已断开";
                            break;
                        }
                        if (plain == null) {
                            reason = "数据完整性校验失败，连接已断开";
                            break;
                        }
                        Object parsed = Json.parse(new String(plain, "UTF-8"));
                        if (!(parsed instanceof Map)) {
                            reason = "收到非法服务器消息";
                            break;
                        }
                        cb.onMessage(Json.obj(parsed));
                    }
                } catch (Exception e) {
                    String msg = e.getMessage();
                    reason = "网络错误：" + (msg == null || msg.isEmpty() ? e.toString() : msg);
                }
                boolean wasClosed = closed;
                closed = true;
                if (!wasClosed) cb.onDisconnected(reason);
            }
        }, "sayschat-reader");
        t.setDaemon(true);
        t.start();
    }

    /** 发送一条 JSON 消息（自动加密并封帧）。 */
    public void send(Map<String, Object> m) throws IOException {
        if (closed) throw new IOException("连接已关闭");
        byte[] encrypted;
        try {
            encrypted = session.encrypt(Json.stringify(m).getBytes("UTF-8"));
        } catch (GeneralSecurityException e) {
            throw new IOException("加密失败: " + e.getMessage());
        }
        synchronized (out) {
            FrameIO.writeFrame(out, encrypted);
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /** 主动关闭连接；不会触发 onDisconnected 回调。 */
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException("连接提前断开");
            off += n;
        }
    }
}
