package sayschat.tools;

import sayschat.server.AccountStore;

import java.io.File;

/**
 * 测试/引导工具：预置一个管理员账户（仅开发用，不打进 jar）。
 * 用法: java -cp dist/SaysChatV4.1.0-Server.jar;tools/classes \
 *       sayschat.tools.SeedAdmin [dir] [user] [pass]
 * 说明: 正常流程是先让用户在客户端注册，再到服务器控制台 /admin 用户名；
 *       本工具用于测试，或服务器无控制台时在“服务器停止状态”下引导首个管理员。
 *       服务器运行中直接改文件不会被已加载的服务器进程看到，请先停止服务器。
 */
public final class SeedAdmin {
    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String user = args.length > 1 ? args[1] : "admin";
        String pass = args.length > 2 ? args[2] : "admin123";
        AccountStore store = new AccountStore(new File(dir));
        if (store.get(user) == null) store.register(user, pass);
        store.setAdmin(user, true);
        System.out.println("OK: 用户 " + user + " 已是管理员 (目录: "
                + new File(dir).getAbsolutePath() + ")");
    }
}
