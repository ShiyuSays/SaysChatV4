package sayschat.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * TCP 帧协议：4 字节大端长度 + 负载。
 * 握手完成后，负载统一是 {@link Crypto.Session} 加密后的数据。
 */
public final class FrameIO {
    /** 单帧上限；附件消息可接近该值，普通聊天正文另有 4096 字符限制。 */
    public static final int MAX_FRAME = FileTransfer.MAX_FRAME_BYTES;

    private FrameIO() {
    }

    public static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        if (payload == null || payload.length > MAX_FRAME) {
            throw new IOException("帧过大: " + (payload == null ? 0 : payload.length));
        }
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(payload.length);
        d.write(payload);
        d.flush();
    }

    /** 读取一帧；对端正常关闭时返回 null。 */
    public static byte[] readFrame(InputStream in) throws IOException {
        try {
            DataInputStream d = new DataInputStream(in);
            int len = d.readInt();
            if (len < 0 || len > MAX_FRAME) {
                throw new IOException("非法帧长度: " + len);
            }
            byte[] buf = new byte[len];
            d.readFully(buf);
            return buf;
        } catch (EOFException e) {
            return null;
        }
    }
}
