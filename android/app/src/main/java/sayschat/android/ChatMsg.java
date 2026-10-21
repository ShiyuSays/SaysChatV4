package sayschat.android;

/** 一条聊天消息（供列表显示）。 */
public final class ChatMsg {
    public static final int KIND_SYS = 0;   // 系统消息（灰）
    public static final int KIND_CHAT = 1;  // 他人发言（黑）
    public static final int KIND_SELF = 2;  // 自己发言（蓝）
    public static final int KIND_TELL = 3;  // 私聊（紫）
    public static final int KIND_SAYS = 4;  // 公告（红）
    public static final int KIND_HELP = 5;  // /help 帮助（绿，竖列）

    public final int kind;
    public final String header;
    public final String body;
    public final long timestamp;

    public ChatMsg(int kind, String header, String body) {
        this.kind = kind;
        this.header = header;
        this.body = body;
        this.timestamp = System.currentTimeMillis();
    }
}
