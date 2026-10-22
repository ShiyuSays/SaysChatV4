package sayschat.common;

/**
 * 图片/文件传输的公共限制。
 *
 * 当前实现把附件以 Base64 放进一条加密 JSON 消息中转发，不落盘到服务器。
 * 单文件上限 5 MiB，单帧上限 8 MiB，足以覆盖聊天图片和常见文档。
 */
public final class FileTransfer {
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    /** 5 MiB 原文 Base64 后约 7 MiB，再留出 JSON/加密头开销。 */
    public static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    public static final int MAX_FILE_NAME_CHARS = 120;
    public static final int MAX_CAPTION_CHARS = 4096;
    public static final int MAX_BASE64_CHARS = ((MAX_FILE_BYTES + 2) / 3) * 4;

    private FileTransfer() {
    }
}
