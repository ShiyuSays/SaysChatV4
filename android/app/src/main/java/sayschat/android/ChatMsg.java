package sayschat.android;

import android.graphics.Bitmap;

/** 一条聊天消息（供列表显示），可携带图片/文件附件。 */
public final class ChatMsg {
    public static final int KIND_SYS = 0;   // 系统消息（灰）
    public static final int KIND_CHAT = 1;  // 他人发言（黑）
    public static final int KIND_SELF = 2;  // 自己发言（蓝）
    public static final int KIND_TELL = 3;  // 私聊（紫）
    public static final int KIND_SAYS = 4;  // 公告（红）
    public static final int KIND_HELP = 5;  // /help 帮助（绿，竖列）
    public static final int KIND_FILE = 6;  // 图片/文件附件（青绿）

    public final int kind;
    public final String header;
    public final String body;
    public final long timestamp;
    /** 是否为服务器同步下来的历史记录，时间显示到日期。 */
    public final boolean historical;

    /** 附件完整字节，用于点击保存；无附件为 null。 */
    public final byte[] attachment;
    public final String attachmentName;
    public final String attachmentMime;
    public final boolean imageAttachment;
    /** 图片缩略图；文件消息或解码失败时为 null。 */
    public final Bitmap thumbnail;

    public ChatMsg(int kind, String header, String body) {
        this(kind, header, body, System.currentTimeMillis(), false,
                null, null, null, false, null);
    }

    public ChatMsg(int kind, String header, String body, byte[] attachment,
                   String attachmentName, String attachmentMime,
                   boolean imageAttachment, Bitmap thumbnail) {
        this(kind, header, body, System.currentTimeMillis(), false,
                attachment, attachmentName, attachmentMime,
                imageAttachment, thumbnail);
    }

    /** 历史记录使用服务器保存的时间，并在列表中显示日期。 */
    public static ChatMsg historical(int kind, String header, String body, long timestamp) {
        return new ChatMsg(kind, header, body,
                timestamp > 0 ? timestamp : System.currentTimeMillis(), true,
                null, null, null, false, null);
    }

    private ChatMsg(int kind, String header, String body, long timestamp,
                    boolean historical, byte[] attachment, String attachmentName,
                    String attachmentMime, boolean imageAttachment, Bitmap thumbnail) {
        this.kind = kind;
        this.header = header;
        this.body = body;
        this.timestamp = timestamp;
        this.historical = historical;
        this.attachment = attachment;
        this.attachmentName = attachmentName;
        this.attachmentMime = attachmentMime;
        this.imageAttachment = imageAttachment;
        this.thumbnail = thumbnail;
    }
}
