package sayschat.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SaysChatV4 服务器端。
 *
 * <ul>
 *   <li>默认监听 TCP 10001，端口可在 sayschat-server.conf 中修改；</li>
 *   <li>首次启动在当前目录生成端口 + 连接密码配置文件；</li>
 *   <li>账户保存在当前目录 sayschat-accounts.dat；</li>
 *   <li>服务器控制台与管理员客户端共用命令执行逻辑。</li>
 * </ul>
 */
public final class SaysChatServer {
    public static final String VERSION = "4.0";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String[] HELP_LINES = {
            "可用命令：",
            "  /kick 用户名     - 踢出在线用户",
            "  /ban 用户名      - 封禁用户（持久化，自动断线）",
            "  /unban 用户名    - 解除用户封禁",
            "  /ip 用户名       - 查看在线用户 IP",
            "  /says 内容       - 向全体广播公告",
            "  /mute 用户名     - 禁言用户（仅限制公聊）",
            "  /chat 用户名     - 解除用户禁言",
            "  /allmute         - 全员禁言（管理员仍可发言）",
            "  /allchat         - 解除全员禁言",
            "  /admin 用户名    - 授予管理员权限",
            "  /deadmin 用户名  - 撤销管理员权限",
            "  /list            - 查看在线用户列表",
            "  /help            - 显示本帮助（别名 /?）",
            "  /stop            - 关闭服务器（仅服务器控制台）"
    };

    final ServerConfig config;
    final AccountStore accounts;
    final ServerSocket serverSocket;
    final Map<String, ClientHandler> clients = new LinkedHashMap<String, ClientHandler>();

    volatile boolean running = true;
    /** 全员禁言状态；管理员始终可以公聊。 */
    volatile boolean allMuted;

    private final AtomicBoolean shutdownDone = new AtomicBoolean(false);

    private SaysChatServer(ServerConfig config, AccountStore accounts) throws IOException {
        this.config = config;
        this.accounts = accounts;
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(config.port));
        this.serverSocket = ss;
    }

    public static void main(String[] args) {
        final File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        System.out.println("============================================================");
        System.out.println("  SaysChatV4 服务器  v" + VERSION + "  (TCP + AES/HMAC)");
        System.out.println("  工作目录: " + dir);
        System.out.println("============================================================");

        final SaysChatServer server;
        try {
            ServerConfig cfg = ServerConfig.load(dir);
            AccountStore accounts = new AccountStore(dir);
            server = new SaysChatServer(cfg, accounts);
        } catch (Exception e) {
            System.out.println("[启动失败] " + e.getMessage());
            System.exit(1);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                server.shutdown();
            }
        }, "shutdown"));

        System.out.println("[服务器] 正在监听 TCP 端口 " + server.config.port + " ...");
        System.out.println("[服务器] 最大同时在线人数: " + server.config.maxOnline);
        System.out.println("[服务器] 控制台输入 /help 查看命令，/stop 关闭服务器");
        if (!server.accounts.hasAdmin()) {
            System.out.println("[提示] 当前还没有管理员。请让用户注册后，在控制台执行 /admin 用户名");
        }
        server.start();
    }

    public static String version() {
        return VERSION;
    }

    void log(String s) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        System.out.println(ts + " " + s);
    }

    private void start() {
        Thread console = new Thread(new Runnable() {
            public void run() {
                consoleLoop();
            }
        }, "sayschat-console");
        console.setDaemon(true);
        console.start();

        while (running) {
            try {
                Socket s = serverSocket.accept();
                ClientHandler h = new ClientHandler(this, s);
                h.start();
            } catch (IOException e) {
                if (running) {
                    log("[错误] 接受连接失败: " + e.getMessage());
                }
            }
        }
        shutdown();
    }

    private void consoleLoop() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        try {
            while (running) {
                String line;
                try {
                    line = reader.readLine();
                } catch (IOException e) {
                    break;
                }
                if (line == null) {
                    System.out.println("[服务器] 控制台输入已关闭，服务器继续运行；停止请使用 /stop 或结束进程。");
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("/")) {
                    int sp = line.indexOf(' ');
                    String cmd = (sp < 0 ? line.substring(1) : line.substring(1, sp))
                            .trim().toLowerCase(Locale.ENGLISH);
                    String arg = sp < 0 ? "" : line.substring(sp + 1).trim();
                    execCommand(cmd, arg, null);
                } else {
                    System.out.println("[服务器] 控制台命令需要以 / 开头，输入 /help 查看帮助。");
                }
            }
        } finally {
            // 控制台结束不影响服务器主循环；后台运行时由 stop 脚本结束进程。
        }
    }

    /**
     * 执行管理命令。requester == null 表示服务器控制台。
     * 客户端调用前已由 ClientHandler 校验管理员身份，此处仍做一次兜底校验。
     */
    void execCommand(String cmd, String args, ClientHandler requester) {
        boolean console = requester == null;
        if (!console && (requester == null || !requester.admin)) {
            reply(requester, "此命令仅管理员或服务器端可用");
            return;
        }
        if (cmd == null || cmd.isEmpty()) {
            reply(requester, "未知命令，输入 /help 查看命令");
            return;
        }
        cmd = cmd.trim().toLowerCase(Locale.ENGLISH);
        args = args == null ? "" : args.trim();

        if ("stop".equals(cmd)) {
            if (!console) {
                reply(requester, "此命令仅服务器控制台可用");
                return;
            }
            stopFromConsole();
            return;
        }
        if ("help".equals(cmd) || "?".equals(cmd)) {
            showHelp(requester);
            return;
        }
        if ("list".equals(cmd)) {
            StringBuilder sb = new StringBuilder("在线用户(" + onlineCount()
                    + "/" + config.maxOnline + "): ");
            synchronized (clients) {
                boolean first = true;
                for (ClientHandler h : clients.values()) {
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append(h.name).append(h.admin ? "[管理员]" : "");
                    if (h.muted) sb.append("[禁言]");
                }
                if (first) sb.append("(无)");
            }
            if (allMuted) sb.append("  | 全员禁言中");
            reply(requester, sb.toString());
            return;
        }
        if ("kick".equals(cmd)) {
            String name = args;
            if (name.isEmpty()) {
                reply(requester, "用法: /kick 用户名");
                return;
            }
            ClientHandler h = find(name);
            if (h == null) {
                reply(requester, "用户 " + name + " 不在线");
                return;
            }
            if (h == requester) {
                reply(requester, "不能对自己执行该命令");
                return;
            }
            h.kickOut("您已被管理员踢出聊天");
            reply(requester, "已踢出 " + name);
            log("[管理] " + actor(requester) + " 踢出了 " + name);
            return;
        }
        if ("ban".equals(cmd)) {
            String name = args;
            if (name.isEmpty()) {
                reply(requester, "用法: /ban 用户名");
                return;
            }
            AccountStore.Account a = accounts.get(name);
            if (a == null) {
                reply(requester, "用户 " + name + " 不存在");
                return;
            }
            if (a.admin && !console) {
                reply(requester, "不能封禁管理员");
                return;
            }
            if (a.banned) {
                reply(requester, name + " 已被封禁");
                return;
            }
            try {
                accounts.setBanned(name, true);
            } catch (IOException e) {
                reply(requester, "保存失败: " + e.getMessage());
                return;
            }
            ClientHandler h = find(name);
            if (h != null) h.kickOut("您已被管理员封禁");
            reply(requester, "已封禁 " + name);
            log("[管理] " + actor(requester) + " 封禁了 " + name);
            return;
        }
        if ("unban".equals(cmd)) {
            String name = args;
            if (name.isEmpty()) {
                reply(requester, "用法: /unban 用户名");
                return;
            }
            AccountStore.Account a = accounts.get(name);
            if (a == null) {
                reply(requester, "用户 " + name + " 不存在");
                return;
            }
            if (!a.banned) {
                reply(requester, name + " 未被封禁");
                return;
            }
            try {
                accounts.setBanned(name, false);
            } catch (IOException e) {
                reply(requester, "保存失败: " + e.getMessage());
                return;
            }
            reply(requester, "已解除对 " + name + " 的封禁");
            log("[管理] " + actor(requester) + " 解除了 " + name + " 的封禁");
            return;
        }
        if ("ip".equals(cmd)) {
            String name = args;
            if (name.isEmpty()) {
                reply(requester, "用法: /ip 用户名");
                return;
            }
            ClientHandler h = find(name);
            if (h == null) {
                reply(requester, "用户 " + name + " 不在线");
                return;
            }
            reply(requester, "用户 " + name + " 的 IP: " + h.ip()
                    + " (端口 " + h.port() + ", " + h.device + ")");
            return;
        }
        if ("says".equals(cmd)) {
            if (args.isEmpty()) {
                reply(requester, "用法: /says 公告内容");
                return;
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("t", "says");
            m.put("from", actor(requester));
            m.put("msg", args);
            broadcast(m);
            if (console) reply(requester, "公告已广播: " + args);
            log("[公告] " + actor(requester) + ": " + args);
            return;
        }
        if ("mute".equals(cmd)) {
            String name = args;
            if (name.isEmpty()) {
                reply(requester, "用法: /mute 用户名");
                return;
            }
            ClientHandler h = find(name);
            if (h == null) {
                reply(requester, "用户 " + name + " 不在线");
                return;
            }
            if (h == requester) {
                reply(requester, "不能对自己执行该命令");
                return;
            }
            if (h.muted) {
                reply(requester, name + " 已在禁言中");
                return;
            }
            h.muted = true;
            h.sendMuteState(true, actor(requester));
            broadcastSysExcept(name + " 已被管理员禁言", h, requester);
            reply(requester, "已禁言 " + name);
            log("[管理] " + actor(requester) + " 禁言了 " + name);
            return;
        }
        if ("chat".equals(cmd)) {
            String name = args;
            if (name.isEmpty()) {
                reply(requester, "用法: /chat 用户名");
                return;
            }
            ClientHandler h = find(name);
            if (h == null) {
                reply(requester, "用户 " + name + " 不在线");
                return;
            }
            if (!h.muted) {
                reply(requester, name + " 未被禁言");
                return;
            }
            h.muted = false;
            h.sendMuteState(false, actor(requester));
            broadcastSysExcept(name + " 已被解除禁言", h, requester);
            reply(requester, "已解除 " + name + " 的禁言");
            log("[管理] " + actor(requester) + " 解除了 " + name + " 的禁言");
            return;
        }
        if ("allmute".equals(cmd)) {
            if (allMuted) {
                reply(requester, "服务器已处于全员禁言状态");
                return;
            }
            allMuted = true;
            broadcastAllMute(true, actor(requester));
            if (console) reply(requester, "已开启全员禁言（管理员仍可发言）");
            log("[管理] " + actor(requester) + " 开启了全员禁言");
            return;
        }
        if ("allchat".equals(cmd)) {
            if (!allMuted) {
                reply(requester, "服务器当前未开启全员禁言");
                return;
            }
            allMuted = false;
            broadcastAllMute(false, actor(requester));
            if (console) reply(requester, "已解除全员禁言");
            log("[管理] " + actor(requester) + " 解除了全员禁言");
            return;
        }
        if ("admin".equals(cmd)) {
            String name = args;
            if (name.isEmpty()) {
                reply(requester, "用法: /admin 用户名");
                return;
            }
            AccountStore.Account a = accounts.get(name);
            if (a == null) {
                reply(requester, "用户 " + name + " 不存在");
                return;
            }
            if (a.admin) {
                reply(requester, name + " 已是管理员");
                return;
            }
            try {
                accounts.setAdmin(name, true);
            } catch (IOException e) {
                reply(requester, "保存失败: " + e.getMessage());
                return;
            }
            ClientHandler h = find(name);
            if (h != null) {
                h.admin = true;
                h.sendRole(true);
            }
            broadcastSysExcept(name + " 已被授予管理员权限", h, requester);
            broadcastUsers();
            reply(requester, "已将 " + name + " 设为管理员");
            log("[管理] " + actor(requester) + " 授予 " + name + " 管理员权限");
            return;
        }
        if ("deadmin".equals(cmd)) {
            String name = args;
            if (name.isEmpty()) {
                reply(requester, "用法: /deadmin 用户名");
                return;
            }
            AccountStore.Account a = accounts.get(name);
            if (a == null) {
                reply(requester, "用户 " + name + " 不存在");
                return;
            }
            if (!a.admin) {
                reply(requester, name + " 不是管理员");
                return;
            }
            try {
                accounts.setAdmin(name, false);
            } catch (IOException e) {
                reply(requester, "保存失败: " + e.getMessage());
                return;
            }
            ClientHandler h = find(name);
            if (h != null) {
                h.admin = false;
                h.sendRole(false);
            }
            broadcastSysExcept(name + " 已被撤销管理员权限", h, requester);
            broadcastUsers();
            reply(requester, "已撤销 " + name + " 的管理员权限");
            log("[管理] " + actor(requester) + " 撤销了 " + name + " 的管理员权限");
            return;
        }
        reply(requester, "未知命令 /" + cmd + "，输入 /help 查看命令");
    }

    private void stopFromConsole() {
        if (!running) return;
        broadcastSys("服务器正在关闭...");
        log("[服务器] 控制台执行 /stop，正在关闭...");
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    /** 幂等关闭：停止监听、通知并断开所有客户端。 */
    void shutdown() {
        if (!shutdownDone.compareAndSet(false, true)) return;
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        List<ClientHandler> all;
        synchronized (clients) {
            all = new ArrayList<ClientHandler>(clients.values());
        }
        for (ClientHandler h : all) {
            h.sendSys("服务器已关闭");
            h.close();
        }
        System.out.println("[服务器] 已关闭。");
    }

    private ClientHandler find(String name) {
        synchronized (clients) {
            return clients.get(name);
        }
    }

    int onlineCount() {
        synchronized (clients) {
            return clients.size();
        }
    }

    /** 当前在线人数是否已达到 maxOnline 上限。 */
    boolean isFull() {
        synchronized (clients) {
            return clients.size() >= config.maxOnline;
        }
    }

    String capacityMessage() {
        return "服务器在线人数已满（上限 " + config.maxOnline + " 人），请稍后再试";
    }

    /** /help：竖列显示；服务器控制台使用 ANSI 绿色，客户端逐行发送。 */
    private void showHelp(ClientHandler requester) {
        if (requester == null) {
            for (String line : HELP_LINES) {
                System.out.println(ANSI_GREEN + "[服务器] " + line + ANSI_RESET);
            }
            System.out.flush();
        } else {
            for (String line : HELP_LINES) {
                requester.sendSys(line);
            }
        }
    }

    private void reply(ClientHandler requester, String msg) {
        if (requester == null) {
            System.out.println("[服务器] " + msg);
        } else {
            requester.sendSys(msg);
        }
    }

    private String actor(ClientHandler requester) {
        return requester == null ? "服务器" : requester.name;
    }

    /** 当前在线用户列表（给 auth / users 消息使用的可序列化结构）。 */
    List<Object> snapshotUsers() {
        List<Object> users = new ArrayList<Object>();
        synchronized (clients) {
            for (ClientHandler h : clients.values()) {
                if (h.name == null) continue;
                Map<String, Object> u = new LinkedHashMap<String, Object>();
                u.put("name", h.name);
                u.put("admin", Boolean.valueOf(h.admin));
                users.add(u);
            }
        }
        return users;
    }

    // -------------------- 广播 --------------------

    void broadcast(Map<String, Object> m) {
        List<ClientHandler> list;
        synchronized (clients) {
            list = new ArrayList<ClientHandler>(clients.values());
        }
        for (ClientHandler h : list) h.send(m);
    }

    void broadcastExcept(Map<String, Object> m, ClientHandler except) {
        List<ClientHandler> list;
        synchronized (clients) {
            list = new ArrayList<ClientHandler>(clients.values());
        }
        for (ClientHandler h : list) {
            if (h != except) h.send(m);
        }
    }

    void broadcastSys(String msg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", "sys");
        m.put("msg", msg);
        broadcast(m);
    }

    /** 向除指定连接外的在线用户发送系统消息。 */
    void broadcastSysExcept(String msg, ClientHandler... except) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", "sys");
        m.put("msg", msg);
        List<ClientHandler> list;
        synchronized (clients) {
            list = new ArrayList<ClientHandler>(clients.values());
        }
        outer:
        for (ClientHandler h : list) {
            for (ClientHandler e : except) {
                if (e != null && h == e) continue outer;
            }
            h.send(m);
        }
    }

    void broadcastAllMute(boolean muted, String by) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", "allmute");
        m.put("muted", Boolean.valueOf(muted));
        m.put("by", by);
        broadcast(m);
    }

    /** 全量推送在线用户列表（权限变化时用）。 */
    void broadcastUsers() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", "users");
        m.put("users", snapshotUsers());
        broadcast(m);
    }
}
