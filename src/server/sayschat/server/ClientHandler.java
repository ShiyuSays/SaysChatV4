package sayschat.server;

import sayschat.common.Crypto;
import sayschat.common.FrameIO;
import sayschat.common.Json;
import sayschat.common.SaysClient;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每个客户端连接对应一个处理线程：
 * 加密握手 -> 登录/注册 -> 消息循环（公聊 / 私聊 / 管理命令）。
 */
public final class ClientHandler extends Thread {
    private static final int MAX_LOGIN_PASSWORD = 128;
    private static final int MAX_MESSAGE = 4096;

    private final SaysChatServer server;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private Crypto.Session session;

    String name;                 // 认证后的用户名
    volatile boolean admin;
    volatile boolean muted;      // 会话级禁言
    volatile String device = "unknown";
    private volatile boolean closed;

    ClientHandler(SaysChatServer server, Socket socket) throws IOException {
        super("client-" + socket.getInetAddress().getHostAddress());
        this.server = server;
        this.socket = socket;
        this.socket.setSoTimeout(30000); // 认证阶段最多 30 秒
        this.socket.setTcpNoDelay(true);
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
        setDaemon(true);
    }

    String ip() {
        return socket.getInetAddress().getHostAddress();
    }

    int port() {
        return socket.getPort();
    }

    @Override
    public void run() {
        try {
            if (!authenticate()) return;
            socket.setSoTimeout(0);
            loop();
        } catch (SocketTimeoutException e) {
            server.log("[连接] " + ip() + " 认证超时，已断开");
        } catch (Exception e) {
            // 客户端主动断开、网络中断等，属于正常情况。
        } finally {
            close();
        }
    }

    /** 握手 + 登录/注册。返回 false 表示认证失败，连接将被关闭。 */
    private boolean authenticate() throws IOException {
        byte[] hello = new byte[25];
        // 协议标记(5) + 盐(16) + PBKDF2 迭代次数(4)
        byte[] magic = SaysClient.PROTOCOL_MAGIC.getBytes("UTF-8");
        System.arraycopy(magic, 0, hello, 0, magic.length);
        byte[] salt = Crypto.randomBytes(Crypto.SALT_BYTES);
        System.arraycopy(salt, 0, hello, 5, salt.length);
        int iterations = Crypto.PBKDF2_ITERATIONS;
        hello[21] = (byte) (iterations >>> 24);
        hello[22] = (byte) (iterations >>> 16);
        hello[23] = (byte) (iterations >>> 8);
        hello[24] = (byte) iterations;
        out.write(hello);
        out.flush();

        byte[] frame = FrameIO.readFrame(in);
        if (frame == null) return false;
        byte[] master = Crypto.pbkdf2(server.config.password.toCharArray(),
                salt, iterations, Crypto.MASTER_KEY_BYTES);
        session = new Crypto.Session(master);
        byte[] plain;
        try {
            plain = session.decrypt(frame);
        } catch (GeneralSecurityException e) {
            throw new IOException("握手数据解密失败");
        }
        if (plain == null) {
            try {
                FrameIO.writeFrame(out, "BADPASS".getBytes("UTF-8"));
            } catch (IOException ignored) {
            }
            server.log("[警告] 连接密码错误，已拒绝 " + ip());
            return false;
        }

        Map<String, Object> request;
        try {
            Object parsed = Json.parse(new String(plain, "UTF-8"));
            if (!(parsed instanceof Map)) throw new IllegalArgumentException();
            request = Json.obj(parsed);
        } catch (RuntimeException e) {
            sendAuthFailure("登录请求格式错误");
            return false;
        }

        String type = Json.str(request, "t");
        String user = sanitizeName(Json.str(request, "user"));
        String password = Json.str(request, "pass");
        boolean register = Json.bool(request, "register", false);
        String dev = sanitizeDevice(Json.str(request, "device"));

        String reason = null;
        AccountStore.Account account = null;
        if (!"login".equals(type)) {
            reason = "非法登录请求";
        } else if (user == null) {
            reason = "用户名不合法（1-20 个字母、数字、汉字或 _ - .）";
        } else if (password == null || password.isEmpty()) {
            reason = "账户密码不能为空";
        } else if (password.length() > MAX_LOGIN_PASSWORD) {
            reason = "账户密码过长";
        } else {
            account = server.accounts.get(user);
            if (register) {
                if (account != null) {
                    reason = "该用户名已被注册，请直接登录";
                } else if (server.isFull()) {
                    // 满员时不创建新账户，避免“注册成功却登录失败”的困惑。
                    reason = server.capacityMessage();
                } else {
                    try {
                        server.accounts.register(user, password);
                        account = server.accounts.get(user);
                        server.log("[注册] 新用户 " + user + " 来自 " + ip() + " (" + dev + ")");
                    } catch (IOException e) {
                        reason = "注册失败: " + e.getMessage();
                    }
                }
            } else {
                if (account == null) {
                    reason = "用户名不存在，请先注册";
                } else if (!server.accounts.checkPassword(user, password)) {
                    reason = "账户密码错误";
                } else if (account.banned) {
                    reason = "您已被封禁，无法登录";
                }
            }
        }
        if (reason != null) {
            sendAuthFailure(reason);
            return false;
        }

        // 在线唯一性、封禁状态和人数上限在锁内再确认一次，避免并发登录产生竞态。
        synchronized (server.clients) {
            AccountStore.Account fresh = server.accounts.get(user);
            if (fresh == null) {
                reason = "账户不存在";
            } else if (fresh.banned) {
                reason = "您已被封禁，无法登录";
            } else if (server.clients.containsKey(user)) {
                reason = "该用户已在线，不能重复登录";
            } else if (server.clients.size() >= server.config.maxOnline) {
                reason = server.capacityMessage();
            } else {
                this.name = user;
                this.admin = fresh.admin;
                this.device = dev;
                server.clients.put(user, this);
            }
        }
        if (reason != null) {
            sendAuthFailure(reason);
            return false;
        }

        // 防止“封禁”与“登录插入在线表”并发时漏掉刚刚上线的账户。
        AccountStore.Account afterInsert = server.accounts.get(user);
        if (afterInsert == null || afterInsert.banned) {
            synchronized (server.clients) {
                if (server.clients.get(user) == this) {
                    server.clients.remove(user);
                }
            }
            this.name = null;
            sendAuthFailure(afterInsert == null ? "账户不存在" : "您已被封禁，无法登录");
            return false;
        }

        Map<String, Object> auth = new LinkedHashMap<String, Object>();
        auth.put("t", "auth");
        auth.put("ok", Boolean.TRUE);
        auth.put("user", name);
        auth.put("admin", Boolean.valueOf(admin));
        auth.put("allMuted", Boolean.valueOf(server.allMuted));
        auth.put("users", server.snapshotUsers());
        send(auth);

        server.log("[上线] " + name + (admin ? "[管理员]" : "") + " 来自 "
                + ip() + ":" + port() + " (" + device + ")");

        Map<String, Object> join = new LinkedHashMap<String, Object>();
        join.put("t", "join");
        join.put("name", name);
        join.put("admin", Boolean.valueOf(admin));
        server.broadcastExcept(join, this);
        return true;
    }

    private void sendAuthFailure(String reason) {
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("t", "auth");
        resp.put("ok", Boolean.FALSE);
        resp.put("reason", reason);
        send(resp);
    }

    private void loop() throws IOException {
        while (server.running && !closed) {
            byte[] frame = FrameIO.readFrame(in);
            if (frame == null) return;
            byte[] plain;
            try {
                plain = session.decrypt(frame);
            } catch (GeneralSecurityException e) {
                server.log("[警告] " + name + " 数据解密错误，断开连接");
                return;
            }
            if (plain == null) {
                server.log("[警告] " + name + " 数据完整性校验失败，断开连接");
                return;
            }
            Map<String, Object> m;
            try {
                Object parsed = Json.parse(new String(plain, "UTF-8"));
                if (!(parsed instanceof Map)) throw new IllegalArgumentException();
                m = Json.obj(parsed);
            } catch (RuntimeException e) {
                sendSys("消息格式错误，已忽略");
                continue;
            }
            String type = Json.str(m, "t");
            try {
                if ("chat".equals(type)) {
                    handleChat(Json.str(m, "msg"));
                } else if ("tell".equals(type)) {
                    handleTell(Json.str(m, "to"), Json.str(m, "msg"));
                } else if ("cmd".equals(type)) {
                    handleCommand(Json.str(m, "cmd"), Json.str(m, "arg"));
                } else if ("quit".equals(type)) {
                    return;
                } else {
                    sendSys("未知消息类型");
                }
            } catch (RuntimeException e) {
                sendSys("操作失败: " + e.getMessage());
            }
        }
    }

    private void handleChat(String msg) {
        if (msg == null || msg.trim().isEmpty()) return;
        if (msg.length() > MAX_MESSAGE) {
            sendSys("消息过长（最多 " + MAX_MESSAGE + " 字符）");
            return;
        }
        if (muted) {
            sendSys("您已被禁言，无法发送消息");
            return;
        }
        if (server.allMuted && !admin) {
            sendSys("当前为全员禁言状态，只有管理员可以发言");
            return;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", "chat");
        m.put("from", name);
        m.put("admin", Boolean.valueOf(admin));
        m.put("msg", msg);
        server.broadcast(m);
        server.log("[聊天] " + name + ": " + oneLine(msg));
    }

    private void handleTell(String to, String msg) {
        if (to != null) to = to.trim();
        if (to == null || to.isEmpty() || msg == null || msg.trim().isEmpty()) {
            sendSys("用法: /tell 用户名 私聊内容");
            return;
        }
        if (msg.length() > MAX_MESSAGE) {
            sendSys("消息过长（最多 " + MAX_MESSAGE + " 字符）");
            return;
        }
        ClientHandler target;
        synchronized (server.clients) {
            target = server.clients.get(to);
        }
        if (target == null) {
            sendSys("用户 " + to + " 不在线");
            return;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", "tell");
        m.put("from", name);
        m.put("to", to);
        m.put("msg", msg);
        target.send(m);
        if (target != this) send(m); // 回显给发送者
        server.log("[私聊] " + name + " -> " + to + ": " + oneLine(msg));
    }

    private void handleCommand(String cmd, String arg) {
        if (cmd == null || cmd.trim().isEmpty()) {
            sendSys("未知命令，输入 /help 查看命令");
            return;
        }
        if (!admin) {
            sendSys("此命令仅管理员或服务器端可用");
            return;
        }
        server.execCommand(cmd.trim(), arg == null ? "" : arg, this);
    }

    // -------------------- 发送 --------------------

    void send(Map<String, Object> m) {
        if (closed || session == null) return;
        try {
            byte[] encrypted = session.encrypt(Json.stringify(m).getBytes("UTF-8"));
            synchronized (out) {
                FrameIO.writeFrame(out, encrypted);
            }
        } catch (Exception e) {
            close();
        }
    }

    void sendSys(String msg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", "sys");
        m.put("msg", msg);
        send(m);
    }

    void sendMuteState(boolean isMuted, String by) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", "mute");
        m.put("muted", Boolean.valueOf(isMuted));
        m.put("by", by);
        send(m);
    }

    void sendRole(boolean isAdmin) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", "role");
        m.put("admin", Boolean.valueOf(isAdmin));
        send(m);
    }

    /** 踢出：先发送原因，再关闭连接。 */
    void kickOut(String reason) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", "kicked");
        m.put("reason", reason);
        send(m);
        close();
    }

    void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        synchronized (server.clients) {
            if (name != null && server.clients.get(name) == this) {
                server.clients.remove(name);
            }
        }
        if (name != null) {
            server.log("[下线] " + name);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("t", "leave");
            m.put("name", name);
            server.broadcast(m);
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /** 用户名：1-20 个字母、数字、汉字或 _ - .，不允许空白和控制字符。 */
    private static String sanitizeName(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.length() > 20) return null;
        if ("服务器".equals(t) || "系统".equals(t)) return null;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.')) {
                return null;
            }
        }
        return t;
    }

    private static String sanitizeDevice(String dev) {
        if (dev == null) return "unknown";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dev.length() && sb.length() < 24; i++) {
            char c = dev.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ' ') {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    private static String oneLine(String s) {
        return s.replace('\r', ' ').replace('\n', ' ');
    }
}
