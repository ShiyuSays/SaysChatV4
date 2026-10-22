package sayschat.tools;

import sayschat.common.Json;
import sayschat.common.SaysClient;

import java.util.Map;

/**
 * 开发用小工具：登录一次并打印账户角色与在线用户，用于验证账户持久化/重启后的登录。
 * 用法: java -cp dist/SaysChatV4.1.0-Server.jar;tools/classes \
 *       sayschat.tools.Probe <host> <port> <连接密码> <用户名> <密码> [register]
 */
public final class Probe {
    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("用法: Probe <host> <port> <connPwd> <user> <pass> [register]");
            System.exit(2);
        }
        boolean register = args.length > 5 && Boolean.parseBoolean(args[5]);
        SaysClient c = new SaysClient(args[0], Integer.parseInt(args[1]), args[2]);
        try {
            c.login(args[3], args[4], register, "probe");
            StringBuilder users = new StringBuilder();
            for (Object o : c.users) {
                Map<String, Object> u = Json.obj(o);
                if (users.length() > 0) users.append(",");
                users.append(Json.str(u, "name"));
                if (Json.bool(u, "admin", false)) users.append("[管理员]");
            }
            System.out.println("LOGIN_OK user=" + c.user + " admin=" + c.admin
                    + " allMuted=" + c.allMuted + " online=" + users);
            System.out.println("EXIT_OK");
        } finally {
            c.close();
        }
    }
}
