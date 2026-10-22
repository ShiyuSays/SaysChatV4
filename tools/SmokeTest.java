package sayschat.tools;

import sayschat.common.Json;
import sayschat.common.SaysClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开发用端到端测试客户端。
 * 前置条件：服务器已启动，admin 账户已存在且为管理员（可先用 SeedAdmin 创建），
 * 并且 bob / carol / dave 在该服务器目录中尚未注册。
 *
 * 用法:
 *   java -cp dist/SaysChatV4.1.0-Server.jar;tools/classes \
 *        sayschat.tools.SmokeTest 127.0.0.1 10001 <连接密码> <管理员名> <管理员密码>
 */
public final class SmokeTest {
    private static String host;
    private static int port;
    private static String connPwd;
    private static int passed;

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("用法: SmokeTest <host> <port> <connPwd> <adminUser> <adminPass>");
            System.exit(2);
        }
        host = args[0];
        port = Integer.parseInt(args[1]);
        connPwd = args[2];
        String adminUser = args[3];
        String adminPass = args[4];

        // ---- 正常登录 ----
        Client admin = new Client("admin", adminUser, adminPass, false);
        check(admin.c.admin, "种子账户应具有管理员权限");
        Client bob = new Client("bob", "bob", "bob-pass-123", true);
        Client carol = new Client("carol", "carol", "carol-pass-123", true);
        Client dave = new Client("dave", "dave", "dave-pass-123", true);
        admin.awaitJoin("bob");
        admin.awaitJoin("carol");
        admin.awaitJoin("dave");
        System.out.println("[PASS] 注册/登录 + 在线用户加入事件");

        // ---- 认证失败路径（必须在前面的账户上线之后测试） ----
        expectAuthFail(adminUser, adminPass, false, "已在线", "管理员重复登录");
        expectAuthFail("bob", "wrong-password", true, "已被注册", "重复注册");
        expectAuthFail("admin", "wrong-account-password", false, "密码错误", "错误账户密码");
        expectConnectionPasswordFail("WRONG-CONNECTION-PASSWORD");
        System.out.println("[PASS] 认证失败路径（重复登录/重复注册/错误密码/错误连接密码）");

        admin.command("list", "");
        admin.awaitSys("在线用户", 3000);
        System.out.println("[PASS] /list 在线列表");

        // ---- 公聊 ----
        clearAll(admin, bob, carol, dave);
        bob.chat("hello-from-bob");
        Map<String, Object> chat = admin.awaitType("chat", 3000);
        eq("bob", Json.str(chat, "from"), "公聊发送者");
        eq("hello-from-bob", Json.str(chat, "msg"), "公聊内容");
        carol.awaitType("chat", 3000);
        bob.awaitType("chat", 3000);
        System.out.println("[PASS] 公聊广播");

        // ---- 私聊（所有用户可用） ----
        clearAll(admin, bob, carol, dave);
        bob.tell("carol", "secret-for-carol");
        Map<String, Object> tell = carol.awaitType("tell", 3000);
        eq("bob", Json.str(tell, "from"), "私聊发送者");
        eq("carol", Json.str(tell, "to"), "私聊接收者");
        eq("secret-for-carol", Json.str(tell, "msg"), "私聊内容");
        bob.awaitType("tell", 3000); // 发送者回显
        check(admin.quietType("tell", 300), "私聊不应广播给其他人");
        System.out.println("[PASS] /tell 私聊（普通用户可用，非广播）");

        // ---- 公告 + 非管理员权限拦截 ----
        clearAll(admin, bob, carol, dave);
        admin.command("says", "server-maintenance-notice");
        Map<String, Object> says = bob.awaitType("says", 3000);
        eq(adminUser, Json.str(says, "from"), "公告发送者");
        carol.awaitType("says", 3000);
        bob.command("says", "not-allowed");
        bob.awaitSys("仅管理员", 3000);
        System.out.println("[PASS] /says 公告 + 普通用户命令拦截");

        // ---- 全员禁言 ----
        clearAll(admin, bob, carol, dave);
        admin.command("allmute", "");
        eq(Boolean.TRUE, bob.awaitAllMute(3000), "bob 收到全员禁言状态");
        eq(Boolean.TRUE, carol.awaitAllMute(3000), "carol 收到全员禁言状态");
        eq(Boolean.TRUE, admin.awaitAllMute(3000), "管理员也收到全员禁言状态");
        clearAll(admin, bob, carol, dave);
        bob.chat("must-be-blocked");
        bob.awaitSys("全员禁言", 3000);
        check(admin.quietType("chat", 400), "全员禁言时非管理员消息不应广播");
        check(carol.quietType("chat", 0), "全员禁言时非管理员消息不应广播给其他用户");
        admin.chat("admin-still-speaks");
        carol.awaitChat("admin", "admin-still-speaks");
        bob.awaitChat("admin", "admin-still-speaks");
        System.out.println("[PASS] /allmute（管理员仍可发言）");

        // ---- 解除全员禁言 ----
        admin.command("allchat", "");
        eq(Boolean.FALSE, bob.awaitAllMute(3000), "bob 收到解除全员禁言");
        clearAll(admin, bob, carol, dave);
        bob.chat("bob-can-speak-again");
        admin.awaitChat("bob", "bob-can-speak-again");
        System.out.println("[PASS] /allchat 解除全员禁言");

        // ---- 单人禁言 / 解除 ----
        clearAll(admin, bob, carol, dave);
        admin.command("mute", "bob");
        eq(Boolean.TRUE, bob.awaitMute(3000), "bob 收到禁言通知");
        admin.awaitSys("已禁言 bob", 3000);
        carol.awaitSys("bob 已被管理员禁言", 3000);
        clearAll(admin, bob, carol, dave);
        bob.chat("muted-message");
        bob.awaitSys("已被禁言", 3000);
        check(admin.quietType("chat", 400), "被禁言用户消息不应广播");
        admin.command("chat", "bob");
        eq(Boolean.FALSE, bob.awaitMute(3000), "bob 收到解除禁言通知");
        admin.awaitSys("已解除 bob 的禁言", 3000);
        clearAll(admin, bob, carol, dave);
        bob.chat("bob-unmuted");
        admin.awaitChat("bob", "bob-unmuted");
        System.out.println("[PASS] /mute + /chat");

        // ---- 授予管理员、实时权限与用户栏更新 ----
        clearAll(admin, bob, carol, dave);
        admin.command("admin", "carol");
        eq(Boolean.TRUE, carol.awaitRole(3000), "carol 收到管理员授权");
        admin.awaitUsersWith("carol", true);
        carol.command("ip", "bob");
        Map<String, Object> ipReply = carol.awaitSys("IP:", 3000);
        check(String.valueOf(ipReply.get("msg")).contains("127.0.0.1"), "/ip 应返回地址");
        clearAll(admin, bob, carol, dave);
        carol.command("allmute", "");
        eq(Boolean.TRUE, bob.awaitAllMute(3000), "新管理员可以执行 /allmute");
        carol.command("allchat", "");
        bob.awaitAllMute(3000);
        System.out.println("[PASS] /admin 授予权限、用户栏角色同步、新管理员命令可用");

        // ---- 撤销管理员 ----
        clearAll(admin, bob, carol, dave);
        admin.command("deadmin", "carol");
        eq(Boolean.FALSE, carol.awaitRole(3000), "carol 收到撤销管理员");
        admin.awaitUsersWith("carol", false);
        carol.command("allmute", "");
        carol.awaitSys("仅管理员", 3000);
        System.out.println("[PASS] /deadmin 撤销权限（服务端强制校验）");

        // ---- 踢出 ----
        dave.clear();
        admin.command("kick", "dave");
        Map<String, Object> kicked = dave.awaitType("kicked", 3000);
        check(String.valueOf(kicked.get("reason")).contains("踢"), "dave 应收到踢出原因");
        admin.awaitSys("已踢出 dave", 3000);
        System.out.println("[PASS] /kick");

        // ---- 封禁 / 解封 ----
        clearAll(admin, bob, carol);
        admin.command("ban", "bob");
        Map<String, Object> bannedKick = bob.awaitType("kicked", 3000);
        check(String.valueOf(bannedKick.get("reason")).contains("封禁"), "bob 应收到封禁原因");
        admin.awaitSys("已封禁 bob", 3000);
        expectAuthFail("bob", "bob-pass-123", false, "封禁", "封禁用户登录");
        admin.command("unban", "bob");
        admin.awaitSys("已解除", 3000);
        Client bob2 = new Client("bob2", "bob", "bob-pass-123", false);
        eq("bob", bob2.c.user, "解封后重新登录");
        System.out.println("[PASS] /ban + /unban");

        // ---- /ip ----
        clearAll(admin);
        admin.command("ip", "bob");
        Map<String, Object> ip = admin.awaitSys("IP:", 3000);
        check(String.valueOf(ip.get("msg")).contains("127.0.0.1"), "/ip 应显示回环地址");
        System.out.println("[PASS] /ip");

        // ---- 客户端不能关闭服务器 ----
        admin.clear();
        admin.command("stop", "");
        admin.awaitSys("仅服务器控制台", 3000);
        System.out.println("[PASS] 客户端 /stop 被拒绝");

        bob2.close();
        carol.close();
        dave.close();
        admin.close();
        System.out.println();
        System.out.println("===== SmokeTest 全部通过: " + passed + " 项断言 =====");
    }

    // ------------------------------------------------------------------

    private static void clearAll(Client... clients) {
        for (Client c : clients) c.clear();
    }

    private static void expectConnectionPasswordFail(String pwd) throws Exception {
        SaysClient c = null;
        try {
            c = new SaysClient(host, port, pwd);
            c.login("admin", "whatever", false, "test");
            throw new AssertionError("错误连接密码不应登录成功");
        } catch (SaysClient.AuthFailed e) {
            check(e.getMessage().contains("连接密码"), "错误连接密码提示: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
    }

    private static void expectAuthFail(String user, String pass, boolean register,
                                       String expect, String what) throws Exception {
        SaysClient c = null;
        try {
            c = new SaysClient(host, port, connPwd);
            c.login(user, pass, register, "test");
            throw new AssertionError(what + " 不应成功");
        } catch (SaysClient.AuthFailed e) {
            check(e.getMessage() != null && e.getMessage().contains(expect),
                    what + " 提示应包含 [" + expect + "]，实际: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
    }

    private static final class Client {
        final String label;
        final SaysClient c;
        final List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        final Object lock = new Object();
        volatile String disconnectReason;

        Client(String label, String user, String pass, boolean register) throws Exception {
            this.label = label;
            SaysClient client = new SaysClient(host, port, connPwd);
            try {
                client.login(user, pass, register, "test");
            } catch (Exception e) {
                client.close();
                throw e;
            }
            this.c = client;
            c.startReader(new SaysClient.Callback() {
                public void onMessage(Map<String, Object> m) {
                    synchronized (lock) {
                        events.add(m);
                        lock.notifyAll();
                    }
                }

                public void onDisconnected(String reason) {
                    synchronized (lock) {
                        disconnectReason = reason;
                        lock.notifyAll();
                    }
                }
            });
        }

        void chat(String msg) throws Exception {
            send(map("t", "chat", "msg", msg));
        }

        void tell(String to, String msg) throws Exception {
            send(map("t", "tell", "to", to, "msg", msg));
        }

        void command(String cmd, String arg) throws Exception {
            send(map("t", "cmd", "cmd", cmd, "arg", arg));
        }

        void send(Map<String, Object> m) throws Exception {
            c.send(m);
        }

        void clear() {
            synchronized (lock) {
                events.clear();
            }
        }

        Map<String, Object> awaitType(final String type, long timeout) throws Exception {
            return await(new Match() {
                public boolean test(Map<String, Object> m) {
                    return type.equals(Json.str(m, "t"));
                }
            }, "type=" + type, timeout);
        }

        Map<String, Object> awaitSys(String needle, long timeout) throws Exception {
            final String n = needle;
            return await(new Match() {
                public boolean test(Map<String, Object> m) {
                    return "sys".equals(Json.str(m, "t"))
                            && Json.str(m, "msg") != null
                            && Json.str(m, "msg").contains(n);
                }
            }, "sys contains " + needle, timeout);
        }

        Map<String, Object> awaitJoin(final String name) throws Exception {
            final String n = name;
            return await(new Match() {
                public boolean test(Map<String, Object> m) {
                    return "join".equals(Json.str(m, "t")) && n.equals(Json.str(m, "name"));
                }
            }, "join " + name, 5000);
        }

        Boolean awaitAllMute(long timeout) throws Exception {
            Map<String, Object> m = awaitType("allmute", timeout);
            return Boolean.valueOf(Json.bool(m, "muted", false));
        }

        Boolean awaitMute(long timeout) throws Exception {
            Map<String, Object> m = awaitType("mute", timeout);
            return Boolean.valueOf(Json.bool(m, "muted", false));
        }

        Boolean awaitRole(long timeout) throws Exception {
            Map<String, Object> m = awaitType("role", timeout);
            return Boolean.valueOf(Json.bool(m, "admin", false));
        }

        Map<String, Object> awaitChat(String from, String msg) throws Exception {
            final String f = from;
            final String text = msg;
            return await(new Match() {
                public boolean test(Map<String, Object> m) {
                    return "chat".equals(Json.str(m, "t"))
                            && f.equals(Json.str(m, "from"))
                            && text.equals(Json.str(m, "msg"));
                }
            }, "chat from " + from + ": " + msg, 5000);
        }

        void awaitUsersWith(final String name, final boolean isAdmin) throws Exception {
            await(new Match() {
                public boolean test(Map<String, Object> m) {
                    if (!"users".equals(Json.str(m, "t"))) return false;
                    Object ul = m.get("users");
                    if (!(ul instanceof List)) return false;
                    for (Object o : (List<?>) ul) {
                        Map<String, Object> u = Json.obj(o);
                        if (name.equals(Json.str(u, "name"))
                                && Json.bool(u, "admin", false) == isAdmin) {
                            return true;
                        }
                    }
                    return false;
                }
            }, "users with " + name + "[admin=" + isAdmin + "]", 5000);
        }

        boolean quietType(String type, long ms) throws Exception {
            long end = System.currentTimeMillis() + ms;
            synchronized (lock) {
                while (true) {
                    for (Map<String, Object> m : events) {
                        if (type.equals(Json.str(m, "t"))) return false;
                    }
                    long remain = end - System.currentTimeMillis();
                    if (remain <= 0) return true;
                    lock.wait(remain);
                }
            }
        }

        Map<String, Object> await(Match match, String what, long timeout) throws Exception {
            long end = System.currentTimeMillis() + timeout;
            synchronized (lock) {
                while (true) {
                    for (int i = 0; i < events.size(); i++) {
                        if (match.test(events.get(i))) {
                            return events.remove(i);
                        }
                    }
                    long remain = end - System.currentTimeMillis();
                    if (remain <= 0) {
                        throw new AssertionError("[" + label + "] 等待超时: " + what
                                + "，已有事件: " + Json.stringify(events));
                    }
                    lock.wait(remain);
                }
            }
        }

        void close() {
            try {
                send(map("t", "quit"));
            } catch (Exception ignored) {
            }
            c.close();
        }
    }

    private interface Match {
        boolean test(Map<String, Object> m);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }

    private static void eq(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + " 不匹配，期望=[" + expected + "] 实际=[" + actual + "]");
        }
        passed++;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
