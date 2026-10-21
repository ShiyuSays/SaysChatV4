package sayschat.common;

/**
 * 电脑端与安卓端共用的 /help 文本。
 * 每条命令单独一行，客户端以绿色显示。
 */
public final class ClientHelp {
    private static final String[] LINES = {
            "可用命令：",
            "【所有用户】",
            "  /tell 用户名 私聊内容  - 给在线用户发送私聊",
            "  /help  - 显示本帮助（别名 /?）",
            "【管理员 / 服务器控制台】",
            "  /kick 用户名  - 踢出在线用户",
            "  /ban 用户名  - 封禁用户",
            "  /unban 用户名  - 解除用户封禁",
            "  /ip 用户名  - 查看在线用户 IP",
            "  /says 内容  - 向全体广播公告",
            "  /mute 用户名  - 禁言用户（仅限制公聊）",
            "  /chat 用户名  - 解除用户禁言",
            "  /allmute  - 全员禁言（管理员仍可发言）",
            "  /allchat  - 解除全员禁言",
            "  /admin 用户名  - 授予管理员权限",
            "  /deadmin 用户名  - 撤销管理员权限",
            "  /list  - 查看在线用户列表",
            "【仅服务器控制台】",
            "  /stop  - 关闭服务器"
    };

    private ClientHelp() {
    }

    /** 返回竖列帮助文本，每行一个命令，使用 '\n' 分隔。 */
    public static String text() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LINES.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(LINES[i]);
        }
        return sb.toString();
    }
}
