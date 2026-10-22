package sayschat.server;

import sayschat.common.FileTransfer;
import sayschat.common.Json;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务器端聊天归档。
 * 在工作目录的 SaysChat 下维护 Chat（聊天记录）、Attach（文件）、Picture（图片）。
 * 聊天记录和附件均以明文落盘，部署时需限制工作目录访问权限。
 */
public final class ChatArchive {
    private static final int HISTORY_MEMORY_LIMIT = 1000;
    private static final int HISTORY_SEND_LIMIT = 200;
    private static final Pattern LEGACY_LINE = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\] "
                    + "\\[(聊天|私聊|公告)\\] (.*)$");

    private final File baseDir;
    private final File chatDir;
    private final File attachDir;
    private final File pictureDir;
    private final File chatFile;
    private final File historyFile;
    private final Deque<Map<String, Object>> history =
            new ArrayDeque<Map<String, Object>>();
    private final Object writeLock = new Object();

    public ChatArchive(File workingDir) throws IOException {
        this.baseDir = new File(workingDir, "SaysChat");
        this.chatDir = new File(baseDir, "Chat");
        this.attachDir = new File(baseDir, "Attach");
        this.pictureDir = new File(baseDir, "Picture");
        ensureDirectory(baseDir);
        ensureDirectory(chatDir);
        ensureDirectory(attachDir);
        ensureDirectory(pictureDir);
        this.chatFile = new File(chatDir, "chat.log");
        if (!chatFile.isFile() && !chatFile.createNewFile()) {
            throw new IOException("无法创建聊天记录文件: " + chatFile.getAbsolutePath());
        }
        restrictFile(chatFile);
        this.historyFile = new File(chatDir, "history.jsonl");
        if (!historyFile.isFile() && !historyFile.createNewFile()) {
            throw new IOException("无法创建历史记录文件: " + historyFile.getAbsolutePath());
        }
        restrictFile(historyFile);
        importLegacyChatLog();
        loadHistory();
    }

    public File chatFile() {
        return chatFile;
    }

    public File attachDir() {
        return attachDir;
    }

    public File pictureDir() {
        return pictureDir;
    }

    /** 公聊记录。 */
    public void chat(String user, boolean admin, String message) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("kind", "chat");
        entry.put("from", user);
        entry.put("admin", Boolean.valueOf(admin));
        entry.put("msg", message);
        recordHistory(entry);
        append("聊天", user + (admin ? "[管理员]" : "") + ": " + message);
    }

    /** 私聊记录，包含发送者、接收者和内容。 */
    public void tell(String from, String to, String message) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("kind", "tell");
        entry.put("from", from);
        entry.put("to", to);
        entry.put("msg", message);
        recordHistory(entry);
        append("私聊", from + " -> " + to + ": " + message);
    }

    /** 管理员或控制台公告。 */
    public void says(String from, String message) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("kind", "says");
        entry.put("from", from);
        entry.put("msg", message);
        recordHistory(entry);
        append("公告", from + ": " + message);
    }

    /** 图片/文件消息只记录元数据和服务器保存位置，不写入文件内容。 */
    public void file(String from, boolean admin, boolean image, String fileName,
                     int size, String caption, File saved) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("kind", "file");
        entry.put("from", from);
        entry.put("admin", Boolean.valueOf(admin));
        entry.put("fileKind", image ? "image" : "file");
        entry.put("name", fileName);
        entry.put("size", Integer.valueOf(size));
        entry.put("msg", caption == null ? "" : caption);
        recordHistory(entry);

        StringBuilder line = new StringBuilder();
        line.append(from).append(admin ? "[管理员]" : "")
                .append(" 发送").append(image ? "图片" : "文件")
                .append(": ").append(fileName)
                .append(" (").append(formatBytes(size)).append(")")
                .append(" -> ").append(relativePath(saved));
        if (caption != null && !caption.trim().isEmpty()) {
            line.append("  说明: ").append(caption.trim());
        }
        append(image ? "图片" : "文件", line.toString());
    }

    /** 返回该用户可见的最近历史记录：公聊/公告/附件所有人可见，私聊仅双方可见。 */
    public List<Map<String, Object>> historyFor(String user) {
        List<Map<String, Object>> visible = new ArrayList<Map<String, Object>>();
        synchronized (writeLock) {
            for (Map<String, Object> entry : history) {
                String kind = Json.str(entry, "kind");
                if ("tell".equals(kind)) {
                    String from = Json.str(entry, "from");
                    String to = Json.str(entry, "to");
                    if (user == null
                            || (!user.equals(from) && !user.equals(to))) {
                        continue;
                    }
                }
                visible.add(new LinkedHashMap<String, Object>(entry));
            }
        }
        if (visible.size() > HISTORY_SEND_LIMIT) {
            return new ArrayList<Map<String, Object>>(
                    visible.subList(visible.size() - HISTORY_SEND_LIMIT, visible.size()));
        }
        return visible;
    }

    /**
     * 保存客户端发送的图片或文件，返回实际文件。
     * 同名文件自动追加 -1、-2 后缀，避免覆盖历史附件。
     */
    public File saveAttachment(boolean image, String rawName, byte[] bytes) throws IOException {
        File dir = image ? pictureDir : attachDir;
        String name = safeFileName(rawName);
        synchronized (writeLock) {
            File target = uniqueFile(dir, name);
            try {
                FileOutputStream out = new FileOutputStream(target);
                try {
                    out.write(bytes);
                    out.flush();
                } finally {
                    out.close();
                }
            } catch (IOException e) {
                target.delete();
                throw e;
            }
            restrictFile(target);
            return target;
        }
    }

    public String relativePath(File file) {
        try {
            String rel = baseDir.toPath().relativize(file.toPath()).toString()
                    .replace('\\', '/');
            return baseDir.getName() + "/" + rel;
        } catch (RuntimeException e) {
            return file.getAbsolutePath();
        }
    }

    private void recordHistory(Map<String, Object> entry) {
        entry.put("time", Long.valueOf(System.currentTimeMillis()));
        String line = Json.stringify(entry) + "\n";
        synchronized (writeLock) {
            history.addLast(entry);
            while (history.size() > HISTORY_MEMORY_LIMIT) {
                history.removeFirst();
            }
            try {
                Files.write(historyFile.toPath(), line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.out.println("[存档] 写入历史索引失败: " + e.getMessage());
            }
        }
    }

    /** 从旧版本的人工可读 chat.log 导入文字历史，方便升级后立即同步。 */
    private void importLegacyChatLog() {
        if (historyFile.length() > 0 || !chatFile.isFile() || chatFile.length() == 0) {
            return;
        }
        Deque<Map<String, Object>> imported = new ArrayDeque<Map<String, Object>>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(chatFile), StandardCharsets.UTF_8));
            String line;
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            while ((line = reader.readLine()) != null) {
                Matcher matcher = LEGACY_LINE.matcher(line);
                if (!matcher.matches()) continue;
                String tag = matcher.group(2);
                String rest = matcher.group(3);
                int colon = rest.indexOf(": ");
                if (colon <= 0) continue;
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                if ("聊天".equals(tag)) {
                    String who = rest.substring(0, colon);
                    boolean admin = who.endsWith("[管理员]");
                    if (admin) who = who.substring(0, who.length() - 5);
                    if (who.isEmpty()) continue;
                    entry.put("kind", "chat");
                    entry.put("from", who);
                    entry.put("admin", Boolean.valueOf(admin));
                    entry.put("msg", rest.substring(colon + 2));
                } else if ("私聊".equals(tag)) {
                    int arrow = rest.indexOf(" -> ");
                    if (arrow <= 0) continue;
                    int secondColon = rest.indexOf(": ", arrow + 4);
                    if (secondColon <= 0) continue;
                    entry.put("kind", "tell");
                    entry.put("from", rest.substring(0, arrow));
                    entry.put("to", rest.substring(arrow + 4, secondColon));
                    entry.put("msg", rest.substring(secondColon + 2));
                } else {
                    entry.put("kind", "says");
                    entry.put("from", rest.substring(0, colon));
                    entry.put("msg", rest.substring(colon + 2));
                }
                try {
                    entry.put("time", Long.valueOf(
                            format.parse(matcher.group(1)).getTime()));
                } catch (Exception e) {
                    entry.put("time", Long.valueOf(System.currentTimeMillis()));
                }
                imported.addLast(entry);
                while (imported.size() > HISTORY_MEMORY_LIMIT) {
                    imported.removeFirst();
                }
            }
        } catch (IOException e) {
            System.out.println("[存档] 读取旧版聊天记录失败: " + e.getMessage());
            return;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (imported.isEmpty()) return;
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> entry : imported) {
            out.append(Json.stringify(entry)).append('\n');
        }
        try {
            Files.write(historyFile.toPath(), out.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            restrictFile(historyFile);
            System.out.println("[存档] 已从 chat.log 导入 " + imported.size() + " 条同步历史。");
        } catch (IOException e) {
            System.out.println("[存档] 导入旧版聊天记录失败: " + e.getMessage());
        }
    }

    private void loadHistory() {        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(historyFile), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    Object parsed = Json.parse(line);
                    if (!(parsed instanceof Map)) continue;
                    history.addLast(Json.obj(parsed));
                    while (history.size() > HISTORY_MEMORY_LIMIT) {
                        history.removeFirst();
                    }
                } catch (RuntimeException ignored) {
                    // 跳过损坏的历史行，保留其余记录。
                }
            }
        } catch (IOException e) {
            System.out.println("[存档] 读取历史索引失败: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void append(String tag, String text) {
        String line = "[" + timestamp() + "] [" + tag + "] " + oneLine(text) + "\n";
        synchronized (writeLock) {
            try {
                Files.write(chatFile.toPath(), line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.out.println("[存档] 写入聊天记录失败: " + e.getMessage());
            }
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private static String oneLine(String text) {
        if (text == null) return "";
        return text.replace("\r", "\\n").replace("\n", "\\n");
    }

    private static String safeFileName(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "attachment";
        String s = raw.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c)) continue;
            if (c == '<' || c == '>' || c == ':' || c == '"' || c == '|'
                    || c == '?' || c == '*') {
                b.append('_');
            } else {
                b.append(c);
            }
        }
        s = b.toString().trim();
        if (s.isEmpty() || ".".equals(s) || "..".equals(s)) return "attachment";
        if (s.length() > FileTransfer.MAX_FILE_NAME_CHARS) {
            s = s.substring(0, FileTransfer.MAX_FILE_NAME_CHARS);
        }
        return s;
    }

    private static File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            candidate = new File(dir, base + "-" + i + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private static String formatBytes(int n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return ((n + 511) / 1024) + " KB";
        int tenth = (n % (1024 * 1024)) * 10 / (1024 * 1024);
        return (n / (1024 * 1024)) + "." + tenth + " MB";
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir.getAbsolutePath());
        }
        restrictDirectory(dir);
    }

    private static void restrictDirectory(File file) {
        try {
            Set<java.nio.file.attribute.PosixFilePermission> perms =
                    PosixFilePermissions.fromString("rwx------");
            Files.setPosixFilePermissions(file.toPath(), perms);
        } catch (Exception ignored) {
            // Windows 或文件系统不支持 POSIX 权限时保留默认权限。
        }
    }

    private static void restrictFile(File file) {
        try {
            Set<java.nio.file.attribute.PosixFilePermission> perms =
                    PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file.toPath(), perms);
        } catch (Exception ignored) {
        }
    }
}
