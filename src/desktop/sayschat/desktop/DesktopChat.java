package sayschat.desktop;

import sayschat.common.ClientHelp;
import sayschat.common.FileTransfer;
import sayschat.common.Json;
import sayschat.common.SaysClient;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 桌面聊天主窗口。
 * 中间为聊天记录，右侧为实时在线用户栏（管理员显示“用户名[管理员]”），底部为输入区。
 */
public final class DesktopChat extends JFrame {
    private static final Color ACCENT = new Color(0x303F9F);
    private static final Color ADMIN_COLOR = new Color(0xB26A00);
    private static final Set<String> ADMIN_COMMANDS = new HashSet<String>(Arrays.asList(
            "kick", "ban", "unban", "ip", "says", "mute", "chat",
            "allmute", "allchat", "admin", "deadmin", "list"));

    private final SaysClient client;
    private final String me;
    private boolean admin;
    private boolean allMuted;
    private boolean muted;
    private final Map<String, Boolean> userAdmin = new LinkedHashMap<String, Boolean>();

    private final DefaultListModel<String> userModel = new DefaultListModel<String>();
    private final JList<String> userList = new JList<String>(userModel);
    private final JTextPane log = new JTextPane();
    private final JTextField input = new JTextField();
    private final JButton sendBtn = new BlueButton("发送");
    private final JButton imageBtn = new JButton("图片");
    private final JButton fileBtn = new JButton("文件");
    private final JButton changePwdBtn = new JButton("修改密码");
    private final JLabel userCount = new JLabel("在线用户: 0");
    private final JLabel modeLabel = new JLabel(" ");
    private final JPanel pendingPanel = new JPanel(new BorderLayout(8, 0));
    private final JLabel pendingLabel = new JLabel();
    private final JButton pendingSendBtn = new JButton("发送附件");
    private final JButton pendingCancelBtn = new JButton("取消");
    private final File saysChatDir = ensureDir(
            new File(System.getProperty("user.dir", "."), "SaysChat"));
    private final File pictureDir = ensureDir(new File(saysChatDir, "Picture"));
    private final File attachDir = ensureDir(new File(saysChatDir, "Attach"));
    private File pendingFile;
    private boolean pendingIsImage;
    private final ExecutorService fileSendExecutor =
            Executors.newSingleThreadExecutor(daemonFactory("sayschat-file-send"));
    private final ExecutorService fileReceiveExecutor =
            Executors.newSingleThreadExecutor(daemonFactory("sayschat-file-receive"));
    private final SimpleAttributeSet stySys = new SimpleAttributeSet();
    private final SimpleAttributeSet styChat = new SimpleAttributeSet();
    private final SimpleAttributeSet stySelf = new SimpleAttributeSet();
    private final SimpleAttributeSet styTell = new SimpleAttributeSet();
    private final SimpleAttributeSet stySays = new SimpleAttributeSet();
    private final SimpleAttributeSet styHelp = new SimpleAttributeSet();
    private final SimpleAttributeSet styFile = new SimpleAttributeSet();

    private volatile boolean closed;
    /** 保证退出流程只执行一次，避免重复发送 quit。 */
    private final AtomicBoolean offline = new AtomicBoolean(false);

    /** 从系统文件管理器把单个图片/文件拖进窗口，加入待发送栏（仍需手动确认发送）。 */
    private final TransferHandler fileDropHandler = new TransferHandler() {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                Transferable transferable = support.getTransferable();
                Object data = transferable.getTransferData(DataFlavor.javaFileListFlavor);
                if (!(data instanceof List)) return false;
                List<?> list = (List<?>) data;
                File first = null;
                int fileCount = 0;
                for (Object item : list) {
                    if (item instanceof File && ((File) item).isFile()) {
                        fileCount++;
                        if (first == null) first = (File) item;
                    }
                }
                if (first == null) {
                    JOptionPane.showMessageDialog(DesktopChat.this,
                            "请拖入一个有效的图片或文件。",
                            "无法添加附件", JOptionPane.WARNING_MESSAGE);
                    return false;
                }
                if (fileCount > 1) {
                    JOptionPane.showMessageDialog(DesktopChat.this,
                            "一次只能拖入一个图片或文件，请逐个添加。",
                            "附件数量过多", JOptionPane.WARNING_MESSAGE);
                    return false;
                }
                setPendingAttachment(first, isImageFile(first));
                return true;
            } catch (Exception e) {
                JOptionPane.showMessageDialog(DesktopChat.this,
                        "读取拖入文件失败: " + errorText(e),
                        "无法添加附件", JOptionPane.WARNING_MESSAGE);
                return false;
            }
        }
    };

    public DesktopChat(SaysClient client) {
        super("SaysChatV4.1.0");
        this.client = client;
        this.me = client.user;
        this.admin = client.admin;
        this.allMuted = client.allMuted;
        for (Object o : client.users) {
            Map<String, Object> u = Json.obj(o);
            userAdmin.put(Json.str(u, "name"), Boolean.valueOf(Json.bool(u, "admin", false)));
        }
        buildUi();
        installDropTargets(getContentPane());
        refreshUsers();
        updateTitle();
        updateMode();
        append(stySys, "欢迎 " + me + (admin ? "[管理员]" : "")
                + " 加入 SaysChatV4.1.0，连接已加密。");
        append(stySys, "输入 /help 查看命令。图片/文件上限 "
                + (FileTransfer.MAX_FILE_BYTES / 1024 / 1024) + " MB。");
        append(stySys, "图片保存目录: " + pictureDir.getAbsolutePath());
        append(stySys, "附件保存目录: " + attachDir.getAbsolutePath());
        client.startReader(new SaysClient.Callback() {
            public void onMessage(final Map<String, Object> m) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        handle(m);
                    }
                });
            }

            public void onDisconnected(final String reason) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        disconnected(reason);
                    }
                });
            }
        });
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                requestQuit();
            }

            /** 兜底：任何路径销毁窗口时都关闭连接，服务器会立即将该用户下线。 */
            public void windowClosed(WindowEvent e) {
                shutdownFileExecutors();
                client.close();
            }
        });
        // JVM 正常退出时也关闭 TCP 连接（关闭窗口主动下线之外的兜底）。
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                client.close();
            }
        }, "sayschat-desktop-offline"));
        input.requestFocusInWindow();
    }

    private void buildUi() {
        StyleConstants.setForeground(stySys, new Color(0x888888));
        StyleConstants.setItalic(stySys, true);
        StyleConstants.setForeground(styChat, new Color(0x222222));
        StyleConstants.setForeground(stySelf, new Color(0x1565C0));
        StyleConstants.setForeground(styTell, new Color(0x6A1B9A));
        StyleConstants.setForeground(stySays, new Color(0xC62828));
        StyleConstants.setBold(stySays, true);
        StyleConstants.setForeground(styHelp, new Color(0x2E7D32));
        StyleConstants.setForeground(styFile, new Color(0x00695C));

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(ACCENT);
        top.setBorder(new EmptyBorder(10, 14, 10, 14));
        JLabel channel = new JLabel("公共频道");
        channel.setForeground(Color.WHITE);
        channel.setFont(channel.getFont().deriveFont(Font.BOLD, 16f));
        JLabel secure = new JLabel("已加密 · TCP");
        secure.setForeground(new Color(0xD7DCF7));
        secure.setFont(secure.getFont().deriveFont(Font.PLAIN, 11f));
        top.add(channel, BorderLayout.WEST);
        top.add(secure, BorderLayout.EAST);

        log.setEditable(false);
        log.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
        log.setBorder(new EmptyBorder(6, 8, 6, 8));
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xDDDDDD)));
        logScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setFixedCellHeight(30);
        userList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                String text = value == null ? "" : value.toString();
                label.setText(text);
                label.setBorder(new EmptyBorder(4, 10, 4, 8));
                if (!isSelected) {
                    label.setBackground(Color.WHITE);
                    label.setForeground(text.endsWith("[管理员]") ? ADMIN_COLOR : new Color(0x333333));
                }
                label.setFont(label.getFont().deriveFont(
                        text.endsWith("[管理员]") ? Font.BOLD : Font.PLAIN, 13f));
                return label;
            }
        });
        JScrollPane userScroll = new JScrollPane(userList);
        userScroll.setBorder(null);
        JPanel userPanel = new JPanel(new BorderLayout());
        userPanel.setPreferredSize(new Dimension(218, 0));
        userPanel.setBackground(Color.WHITE);
        userPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(0xDDDDDD)), "在线用户"));
        userCount.setBorder(new EmptyBorder(6, 8, 4, 8));
        userCount.setFont(userCount.getFont().deriveFont(Font.BOLD, 12f));
        modeLabel.setBorder(new EmptyBorder(5, 8, 7, 8));
        modeLabel.setFont(modeLabel.getFont().deriveFont(Font.PLAIN, 11f));
        userPanel.add(userCount, BorderLayout.NORTH);
        userPanel.add(userScroll, BorderLayout.CENTER);
        changePwdBtn.setFocusPainted(false);
        changePwdBtn.setToolTipText("修改当前账户密码");
        JPanel changePwdWrap = new JPanel(new BorderLayout());
        changePwdWrap.setOpaque(false);
        changePwdWrap.setBorder(new EmptyBorder(0, 8, 8, 8));
        changePwdWrap.add(changePwdBtn, BorderLayout.CENTER);
        JPanel userBottom = new JPanel(new BorderLayout());
        userBottom.setOpaque(false);
        userBottom.add(modeLabel, BorderLayout.NORTH);
        userBottom.add(changePwdWrap, BorderLayout.SOUTH);
        userPanel.add(userBottom, BorderLayout.SOUTH);

        input.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
        sendBtn.setPreferredSize(new Dimension(78, 34));
        imageBtn.setPreferredSize(new Dimension(62, 34));
        fileBtn.setPreferredSize(new Dimension(62, 34));
        imageBtn.setFont(imageBtn.getFont().deriveFont(Font.PLAIN, 12f));
        fileBtn.setFont(fileBtn.getFont().deriveFont(Font.PLAIN, 12f));
        imageBtn.setFocusPainted(false);
        fileBtn.setFocusPainted(false);
        imageBtn.setToolTipText("选择图片发送（会压缩为 JPEG）");
        fileBtn.setToolTipText("选择文件发送（最大 5 MB）");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        actions.setOpaque(false);
        actions.add(imageBtn);
        actions.add(fileBtn);
        actions.add(sendBtn);
        JPanel inputRow = new JPanel(new BorderLayout(5, 0));
        inputRow.setBorder(new EmptyBorder(8, 8, 4, 8));
        inputRow.add(input, BorderLayout.CENTER);
        inputRow.add(actions, BorderLayout.EAST);

        pendingPanel.setBackground(new Color(0xFFF8E1));
        pendingPanel.setBorder(new EmptyBorder(7, 10, 7, 10));
        pendingPanel.setVisible(false);
        pendingLabel.setForeground(new Color(0x6D4C41));
        pendingLabel.setFont(pendingLabel.getFont().deriveFont(Font.PLAIN, 12f));
        pendingSendBtn.setPreferredSize(new Dimension(96, 28));
        pendingCancelBtn.setPreferredSize(new Dimension(64, 28));
        JPanel pendingActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        pendingActions.setOpaque(false);
        pendingActions.add(pendingCancelBtn);
        pendingActions.add(pendingSendBtn);
        pendingPanel.add(pendingLabel, BorderLayout.CENTER);
        pendingPanel.add(pendingActions, BorderLayout.EAST);

        JLabel hint = new JLabel("Enter 发送文字 | 可拖入单个图片/文件到窗口 | 添加附件后需点“发送附件”确认");
        hint.setForeground(new Color(0x888888));
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        hint.setBorder(new EmptyBorder(0, 10, 7, 10));

        JPanel south = new JPanel(new BorderLayout());
        south.add(pendingPanel, BorderLayout.NORTH);
        south.add(inputRow, BorderLayout.CENTER);
        south.add(hint, BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(logScroll, BorderLayout.CENTER);
        add(userPanel, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);

        ActionListener sendAction = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                sendText();
            }
        };
        sendBtn.addActionListener(sendAction);
        input.addActionListener(sendAction);
        imageBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                chooseAttachment(true);
            }
        });
        fileBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                chooseAttachment(false);
            }
        });
        pendingSendBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                confirmPendingAttachment();
            }
        });
        pendingCancelBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                clearPendingAttachment();
            }
        });
        changePwdBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showChangePasswordDialog();
            }
        });
        setSize(980, 620);
        setMinimumSize(new Dimension(780, 480));
        setLocationRelativeTo(null);
    }

    private void updateTitle() {
        String suffix = admin ? "[管理员]" : "";
        setTitle("SaysChatV4.1.0 - " + me + suffix + (allMuted ? " · 全员禁言" : ""));
    }

    private void updateMode() {
        if (muted) {
            modeLabel.setText("您已被禁言");
            modeLabel.setForeground(new Color(0xC62828));
        } else if (allMuted) {
            modeLabel.setText("全员禁言中（管理员可发言）");
            modeLabel.setForeground(new Color(0xC62828));
        } else {
            modeLabel.setText("公共频道 · 正常发言");
            modeLabel.setForeground(new Color(0x2E7D32));
        }
    }

    private void append(SimpleAttributeSet style, String text) {
        try {
            StyledDocument doc = log.getStyledDocument();
            String ts = new SimpleDateFormat("HH:mm:ss").format(new Date());
            doc.insertString(doc.getLength(), "[" + ts + "] ", stySys);
            doc.insertString(doc.getLength(), text + "\n", style);
            log.setCaretPosition(doc.getLength());
        } catch (BadLocationException ignored) {
        }
    }

    /** 收到公告、私聊或公聊中被 @ 时播放系统提示音。 */
    private void playNotificationSound() {
        try {
            Toolkit.getDefaultToolkit().beep();
        } catch (Exception ignored) {
            // 没有可用声音设备时不影响消息显示。
        }
    }

    private boolean mentionsMe(String message) {
        if (message == null || me == null) return false;
        String lowerMessage = message.toLowerCase(Locale.ENGLISH);
        String lowerMe = me.toLowerCase(Locale.ENGLISH);
        for (String at : new String[]{"@", "＠"}) {
            int from = 0;
            while (true) {
                int index = lowerMessage.indexOf(at + lowerMe, from);
                if (index < 0) break;
                int end = index + at.length() + lowerMe.length();
                if (end >= message.length()) return true;
                char next = message.charAt(end);
                if (!Character.isLetterOrDigit(next) && next != '_') return true;
                from = end;
            }
        }
        return false;
    }

    /** 显示服务器同步下来的历史记录；时间使用记录原始时间。 */
    private void handleHistory(Map<String, Object> m) {
        Object raw = m.get("entries");
        if (!(raw instanceof List)) return;
        List<?> entries = (List<?>) raw;
        if (entries.isEmpty()) return;
        append(stySys, "—— 服务器聊天记录（最近 " + entries.size() + " 条）——");
        for (Object item : entries) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> entry = Json.obj(item);
            String kind = Json.str(entry, "kind");
            String from = Json.str(entry, "from");
            String msg = Json.str(entry, "msg");
            if (msg == null) msg = "";
            boolean entryAdmin = Json.bool(entry, "admin", false);
            String who = (from == null ? "未知用户" : from)
                    + (entryAdmin ? "[管理员]" : "");
            long time = longValue(entry.get("time"));
            if ("chat".equals(kind)) {
                appendHistoryLine(from != null && from.equals(me) ? stySelf : styChat,
                        time, who + ": " + msg);
            } else if ("tell".equals(kind)) {
                String to = Json.str(entry, "to");
                if (from != null && from.equals(me)) {
                    appendHistoryLine(styTell, time, "你对 " + to + " 私聊: " + msg);
                } else {
                    appendHistoryLine(styTell, time,
                            (from == null ? "未知用户" : from) + " 对你私聊: " + msg);
                }
            } else if ("says".equals(kind)) {
                appendHistoryLine(stySays, time,
                        "【公告】" + (from == null ? "服务器" : from) + ": " + msg);
            } else if ("file".equals(kind)) {
                boolean image = "image".equals(Json.str(entry, "fileKind"));
                String name = Json.str(entry, "name");
                int size = intValue(entry.get("size"));
                StringBuilder text = new StringBuilder();
                text.append(who).append(" 发送").append(image ? "图片" : "文件")
                        .append(": ").append(name == null ? "attachment" : name)
                        .append(" (").append(formatBytes(size)).append(")");
                if (!msg.isEmpty()) text.append("\n").append(msg);
                text.append("  [历史附件]");
                appendHistoryLine(styFile, time, text.toString());
            }
        }
        append(stySys, "—— 以上为服务器保存的历史记录 ——");
    }

    private void appendHistoryLine(SimpleAttributeSet style, long time, String text) {
        try {
            StyledDocument doc = log.getStyledDocument();
            long stamp = time > 0 ? time : System.currentTimeMillis();
            String ts = new SimpleDateFormat("MM-dd HH:mm").format(new Date(stamp));
            doc.insertString(doc.getLength(), "[" + ts + "] ", stySys);
            doc.insertString(doc.getLength(), text + "\n", style);
            log.setCaretPosition(doc.getLength());
        } catch (BadLocationException ignored) {
        }
    }

    private static long longValue(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private static int intValue(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private void handle(Map<String, Object> m) {
        if (closed) return;
        String t = Json.str(m, "t");
        if ("history".equals(t)) {
            handleHistory(m);
        } else if ("chat".equals(t)) {
            String from = Json.str(m, "from");
            boolean a = Json.bool(m, "admin", false);
            String who = from + (a ? "[管理员]" : "");
            String msg = Json.str(m, "msg");
            append(from != null && from.equals(me) ? stySelf : styChat,
                    who + ": " + msg);
            if (from != null && !from.equals(me) && mentionsMe(msg)) {
                playNotificationSound();
            }
        } else if ("file".equals(t)) {
            handleIncomingFile(m);
        } else if ("tell".equals(t)) {
            String from = Json.str(m, "from");
            String to = Json.str(m, "to");
            String msg = Json.str(m, "msg");
            if (from != null && from.equals(me)) {
                append(styTell, "你对 " + to + " 私聊: " + msg);
            } else {
                append(styTell, from + " 对你私聊: " + msg);
                playNotificationSound();
            }
        } else if ("says".equals(t)) {
            String from = Json.str(m, "from");
            append(stySays, "【公告】" + from + ": " + Json.str(m, "msg"));
            if (from == null || !from.equals(me)) {
                playNotificationSound();
            }
        } else if ("sys".equals(t)) {
            append(stySys, Json.str(m, "msg"));
        } else if ("join".equals(t)) {
            String name = Json.str(m, "name");
            boolean a = Json.bool(m, "admin", false);
            userAdmin.put(name, Boolean.valueOf(a));
            refreshUsers();
            append(stySys, name + (a ? "[管理员]" : "") + " 加入了聊天");
        } else if ("leave".equals(t)) {
            String name = Json.str(m, "name");
            userAdmin.remove(name);
            refreshUsers();
            append(stySys, name + " 离开了聊天");
        } else if ("users".equals(t)) {
            userAdmin.clear();
            Object ul = m.get("users");
            if (ul instanceof List) {
                for (Object o : (List<?>) ul) {
                    Map<String, Object> u = Json.obj(o);
                    userAdmin.put(Json.str(u, "name"), Boolean.valueOf(Json.bool(u, "admin", false)));
                }
            }
            refreshUsers();
        } else if ("role".equals(t)) {
            admin = Json.bool(m, "admin", false);
            client.admin = admin;
            updateTitle();
            updateMode();
            append(stySys, admin ? "您已被授予管理员权限" : "您已被撤销管理员权限");
        } else if ("allmute".equals(t)) {
            allMuted = Json.bool(m, "muted", false);
            updateTitle();
            updateMode();
            String by = Json.str(m, "by");
            append(stySys, (by == null ? "管理员" : by)
                    + (allMuted ? " 开启了全员禁言（管理员仍可发言）"
                    : " 解除了全员禁言"));
        } else if ("mute".equals(t)) {
            muted = Json.bool(m, "muted", false);
            updateMode();
            append(stySys, muted ? "您已被管理员禁言" : "您已被解除禁言");
        } else if ("kicked".equals(t)) {
            String reason = Json.str(m, "reason");
            closed = true;
            client.close();
            JOptionPane.showMessageDialog(this,
                    reason == null ? "您已被踢出聊天" : reason,
                    "已被移出", JOptionPane.WARNING_MESSAGE);
            dispose();
            new DesktopLogin().setVisible(true);
        }
    }

    /** 接收图片/文件：后台解码、保存并生成缩略图，再回到 EDT 显示。 */
    private void handleIncomingFile(final Map<String, Object> m) {
        final String from = Json.str(m, "from");
        final boolean fromAdmin = Json.bool(m, "admin", false);
        final boolean image = "image".equals(Json.str(m, "kind"));
        final String name = safeFileName(Json.str(m, "name"));
        final String caption = Json.str(m, "msg") == null ? "" : Json.str(m, "msg");
        final String data = Json.str(m, "data");
        if (data == null || data.isEmpty()) {
            append(stySys, "收到空的附件消息");
            return;
        }
        try {
            fileReceiveExecutor.execute(new Runnable() {
                public void run() {
                    byte[] bytes;
                    try {
                        bytes = Base64.getDecoder().decode(data);
                    } catch (IllegalArgumentException e) {
                        showFileError("附件数据损坏，无法显示");
                        return;
                    }
                    if (bytes.length == 0 || bytes.length > FileTransfer.MAX_FILE_BYTES) {
                        showFileError("附件大小不合法，已忽略");
                        return;
                    }
                    final boolean mine = from != null && from.equals(me);
                    File saved = null;
                    String saveError = null;
                    if (!mine) {
                        try {
                            saved = saveDownload(name, bytes, image);
                        } catch (IOException e) {
                            saveError = errorText(e);
                        }
                    }
                    BufferedImage thumbnail = null;
                    if (image) {
                        try {
                            thumbnail = loadThumbnail(bytes);
                        } catch (IOException ignored) {
                        }
                    }
                    final File savedFile = saved;
                    final String savedError = saveError;
                    final BufferedImage thumb = thumbnail;
                    final byte[] finalBytes = bytes;
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            if (closed) return;
                            StringBuilder text = new StringBuilder();
                            String who = from == null ? "未知用户" : from;
                            if (fromAdmin) who += "[管理员]";
                            if (mine) {
                                text.append("你发送了").append(image ? "图片" : "文件");
                            } else {
                                text.append(who).append(" 发送了").append(image ? "图片" : "文件");
                            }
                            text.append(": ").append(name)
                                    .append(" (").append(formatBytes(finalBytes.length)).append(")");
                            if (!caption.isEmpty()) {
                                text.append("\n").append(caption);
                            }
                            if (savedFile != null) {
                                text.append("\n已保存到: ").append(savedFile.getAbsolutePath());
                            } else if (savedError != null) {
                                text.append("\n保存失败: ").append(savedError);
                            }
                            append(styFile, text.toString());
                            if (thumb != null) appendImage(thumb);
                        }
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
            // 窗口正在关闭。
        }
    }

    private void showFileError(final String message) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if (!closed) append(stySys, message);
            }
        });
    }

    private void appendImage(BufferedImage image) {
        try {
            StyledDocument doc = log.getStyledDocument();
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            StyleConstants.setIcon(attrs, new ImageIcon(image));
            doc.insertString(doc.getLength(), "    ", styFile);
            doc.insertString(doc.getLength(), "\uFFFC\n", attrs);
            log.setCaretPosition(doc.getLength());
        } catch (BadLocationException ignored) {
        }
    }

    private void chooseAttachment(final boolean image) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(image ? "选择要发送的图片" : "选择要发送的文件");
        if (image) {
            chooser.setFileFilter(new FileNameExtensionFilter(
                    "图片文件 (*.jpg, *.jpeg, *.png, *.gif, *.bmp)",
                    "jpg", "jpeg", "png", "gif", "bmp"));
        } else {
            chooser.setAcceptAllFileFilterUsed(true);
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File selected = chooser.getSelectedFile();
        if (selected == null || !selected.isFile()) return;
        setPendingAttachment(selected, image);
    }

    /** 把文件放入待发送栏；此时不会读取或发送，必须再点“发送附件”。 */
    private void setPendingAttachment(File selected, boolean image) {
        if (selected == null || !selected.isFile()) {
            JOptionPane.showMessageDialog(this, "请选择一个有效的文件。",
                    "无法添加附件", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (selected.length() <= 0) {
            JOptionPane.showMessageDialog(this, "文件为空，无法发送。",
                    "无法添加附件", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!image && selected.length() > FileTransfer.MAX_FILE_BYTES) {
            JOptionPane.showMessageDialog(this,
                    "文件超过 " + (FileTransfer.MAX_FILE_BYTES / 1024 / 1024) + " MB 上限。",
                    "文件过大", JOptionPane.WARNING_MESSAGE);
            return;
        }
        pendingFile = selected;
        pendingIsImage = image;
        pendingLabel.setText("待发送" + (image ? "图片" : "文件") + ": "
                + selected.getName() + " (" + formatBytes(
                        (int) Math.min(Integer.MAX_VALUE, selected.length()))
                + ") — 可在输入框填写说明，点击“发送附件”确认");
        pendingPanel.setVisible(true);
        revalidate();
        repaint();
    }

    private static boolean isImageFile(File file) {
        String name = file.getName().toLowerCase(Locale.ENGLISH);
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".gif")
                || name.endsWith(".bmp");
    }

    /** 让窗口内所有子组件都能接收文件拖拽。 */
    private void installDropTargets(Component component) {
        if (component instanceof JComponent) {
            ((JComponent) component).setTransferHandler(fileDropHandler);
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                installDropTargets(child);
            }
        }
    }

    /** 用户点击“发送附件”后，才真正读取、加密并发送待发送附件。 */
    private void confirmPendingAttachment() {
        if (pendingFile == null) return;
        final File selected = pendingFile;
        final boolean image = pendingIsImage;
        final String caption = input.getText().trim();
        if (caption.length() > FileTransfer.MAX_CAPTION_CHARS) {
            JOptionPane.showMessageDialog(this,
                    "说明文字最多 " + FileTransfer.MAX_CAPTION_CHARS + " 个字符。",
                    "说明过长", JOptionPane.WARNING_MESSAGE);
            return;
        }
        clearPendingAttachment();
        input.setText("");
        append(stySys, "正在处理" + (image ? "图片" : "文件") + ": " + selected.getName()
                + " ...");
        try {
            fileSendExecutor.execute(new Runnable() {
                public void run() {
                    try {
                        UploadData upload = image
                                ? prepareImageUpload(selected)
                                : prepareFileUpload(selected);
                        Map<String, Object> message = new LinkedHashMap<String, Object>();
                        message.put("t", "file");
                        message.put("kind", upload.kind);
                        message.put("name", upload.name);
                        message.put("mime", upload.mime);
                        message.put("msg", caption);
                        message.put("data", Base64.getEncoder().encodeToString(upload.bytes));
                        client.send(message);
                    } catch (final Exception e) {
                        SwingUtilities.invokeLater(new Runnable() {
                            public void run() {
                                if (!closed) {
                                    append(stySys, "发送附件失败: " + errorText(e));
                                }
                            }
                        });
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            append(stySys, "发送失败: 窗口正在关闭");
        }
    }

    private void clearPendingAttachment() {
        pendingFile = null;
        pendingIsImage = false;
        pendingPanel.setVisible(false);
        revalidate();
        repaint();
    }

    /** 图片统一缩放为最长边 1600 像素的 JPEG，避免手机原图过大。 */
    private static UploadData prepareImageUpload(File file) throws IOException {
        if (file.length() <= 0) throw new IOException("文件为空");
        BufferedImage source = ImageIO.read(file);
        if (source == null) throw new IOException("无法识别图片格式");
        BufferedImage scaled = scaleImage(source, 1600, 1600);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(scaled, "jpg", out)) throw new IOException("图片编码失败");
        byte[] bytes = out.toByteArray();
        if (bytes.length > FileTransfer.MAX_FILE_BYTES) {
            throw new IOException("图片压缩后仍超过 "
                    + (FileTransfer.MAX_FILE_BYTES / 1024 / 1024) + " MB");
        }
        String base = file.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        if (base.trim().isEmpty()) base = "image";
        return new UploadData(bytes, base + ".jpg", "image/jpeg", "image");
    }

    private static UploadData prepareFileUpload(File file) throws IOException {
        if (file.length() <= 0) throw new IOException("文件为空");
        if (file.length() > FileTransfer.MAX_FILE_BYTES) {
            throw new IOException("文件超过 " + (FileTransfer.MAX_FILE_BYTES / 1024 / 1024)
                    + " MB 上限");
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length > FileTransfer.MAX_FILE_BYTES) {
            throw new IOException("文件超过 " + (FileTransfer.MAX_FILE_BYTES / 1024 / 1024)
                    + " MB 上限");
        }
        String mime = null;
        try {
            mime = Files.probeContentType(file.toPath());
        } catch (IOException ignored) {
        }
        if (mime == null || mime.trim().isEmpty()) mime = "application/octet-stream";
        return new UploadData(bytes, file.getName(), mime, "file");
    }

    private static BufferedImage loadThumbnail(byte[] bytes) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null) return null;
        return scaleImage(source, 360, 260);
    }

    /** 等比缩放并铺白底，返回 RGB 图，便于 JPEG 和 Swing 显示。 */
    private static BufferedImage scaleImage(BufferedImage source, int maxW, int maxH) {
        int w = source.getWidth();
        int h = source.getHeight();
        double scale = Math.min(1.0, Math.min((double) maxW / w, (double) maxH / h));
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, tw, th);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private File saveDownload(String rawName, byte[] bytes, boolean image) throws IOException {
        File dir = image ? pictureDir : attachDir;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建保存目录: " + dir.getAbsolutePath());
        }
        File target = uniqueFile(dir, safeFileName(rawName));
        OutputStream out = new FileOutputStream(target);
        try {
            out.write(bytes);
            out.flush();
        } finally {
            out.close();
        }
        return target;
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

    private static File ensureDir(File dir) {
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
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

    private static String formatBytes(int n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return ((n + 511) / 1024) + " KB";
        int tenth = (n % (1024 * 1024)) * 10 / (1024 * 1024);
        return (n / (1024 * 1024)) + "." + tenth + " MB";
    }

    private static String errorText(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = e.getClass().getSimpleName();
        }
        return message;
    }

    private static ThreadFactory daemonFactory(final String name) {
        return new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        };
    }

    private void shutdownFileExecutors() {
        fileSendExecutor.shutdownNow();
        fileReceiveExecutor.shutdownNow();
    }

    private static final class UploadData {
        final byte[] bytes;
        final String name;
        final String mime;
        final String kind;

        UploadData(byte[] bytes, String name, String mime, String kind) {
            this.bytes = bytes;
            this.name = name;
            this.mime = mime;
            this.kind = kind;
        }
    }

    private void disconnected(String reason) {
        if (closed) return;
        closed = true;
        input.setEnabled(false);
        sendBtn.setEnabled(false);
        append(stySys, reason);
        JOptionPane.showMessageDialog(this, reason, "连接断开", JOptionPane.WARNING_MESSAGE);
        dispose();
        new DesktopLogin().setVisible(true);
    }

    private void showChangePasswordDialog() {
        if (closed) return;
        JPasswordField oldField = new JPasswordField(18);
        JPasswordField newField = new JPasswordField(18);
        JPasswordField confirmField = new JPasswordField(18);
        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel("原密码:"));
        form.add(oldField);
        form.add(new JLabel("新密码:"));
        form.add(newField);
        form.add(new JLabel("确认新密码:"));
        form.add(confirmField);
        JLabel note = new JLabel("<html><span style='color:#777777;font-size:10px'>"
                + "密码修改成功后，下次登录请使用新密码。</span></html>");
        form.add(note);
        int result = JOptionPane.showConfirmDialog(this, form, "修改密码",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;
        String oldPassword = new String(oldField.getPassword());
        String newPassword = new String(newField.getPassword());
        String confirmPassword = new String(confirmField.getPassword());
        if (oldPassword.isEmpty()) {
            warnPassword("请填写原密码");
            return;
        }
        if (newPassword.isEmpty()) {
            warnPassword("请填写新密码");
            return;
        }
        if (newPassword.length() > 128) {
            warnPassword("新密码最多 128 个字符");
            return;
        }
        if (!newPassword.equals(confirmPassword)) {
            warnPassword("两次输入的新密码不一致");
            return;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", "passwd");
        m.put("old", oldPassword);
        m.put("new", newPassword);
        send(m);
        append(stySys, "正在提交密码修改...");
    }

    private void warnPassword(String message) {
        JOptionPane.showMessageDialog(this, message, "修改密码", JOptionPane.WARNING_MESSAGE);
    }

    private void sendText() {
        if (closed) return;
        String text = input.getText().trim();
        if (text.isEmpty()) return;
        input.setText("");
        if (text.startsWith("/")) {
            handleCommand(text);
        } else {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("t", "chat");
            m.put("msg", text);
            send(m);
        }
    }

    private void handleCommand(String line) {
        String body = line.substring(1).trim();
        int sp = body.indexOf(' ');
        String cmd = (sp < 0 ? body : body.substring(0, sp)).toLowerCase();
        String arg = sp < 0 ? "" : body.substring(sp + 1).trim();

        if ("tell".equals(cmd)) {
            int sp2 = arg.indexOf(' ');
            String to = sp2 < 0 ? arg : arg.substring(0, sp2);
            String msg = sp2 < 0 ? "" : arg.substring(sp2 + 1).trim();
            if (to.isEmpty() || msg.isEmpty()) {
                append(stySys, "用法: /tell 用户名 私聊内容");
                return;
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("t", "tell");
            m.put("to", to);
            m.put("msg", msg);
            send(m);
            return;
        }
        if ("help".equals(cmd) || "?".equals(cmd)) {
            append(styHelp, ClientHelp.text());
            return;
        }
        if ("stop".equals(cmd)) {
            append(stySys, "/stop 仅服务器控制台可用");
            return;
        }
        if (ADMIN_COMMANDS.contains(cmd)) {
            if (!admin) {
                append(stySys, "该命令仅管理员或服务器端可用");
                return;
            }
            boolean noArg = "allmute".equals(cmd) || "allchat".equals(cmd) || "list".equals(cmd);
            if (arg.isEmpty() && !noArg) {
                append(stySys, "says".equals(cmd) ? "用法: /says 公告内容" : "用法: /" + cmd + " 用户名");
                return;
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("t", "cmd");
            m.put("cmd", cmd);
            m.put("arg", arg);
            send(m);
            return;
        }
        append(stySys, "未知命令 /" + cmd + "，输入 /help 查看命令");
    }

    private void send(Map<String, Object> m) {
        try {
            client.send(m);
        } catch (Exception e) {
            append(stySys, "发送失败: " + e.getMessage());
        }
    }

    private void refreshUsers() {
        userModel.clear();
        for (Map.Entry<String, Boolean> e : userAdmin.entrySet()) {
            String name = e.getKey();
            if (name == null) continue;
            userModel.addElement(name + (e.getValue().booleanValue() ? "[管理员]" : ""));
        }
        userCount.setText("在线用户: " + userModel.size());
    }

    /**
     * 主动下线：先发送 quit 让服务器立刻广播离开事件，再关闭 TCP 连接。
     * 即使 quit 发送失败，关闭 socket 也会让服务器检测到断开并把用户下线。
     */
    private void goOffline() {
        if (!offline.compareAndSet(false, true)) return;
        try {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("t", "quit");
            client.send(m);
        } catch (Exception ignored) {
        } finally {
            client.close();
        }
    }

    /**
     * 点击窗口关闭按钮时先确认；确认后下线并返回登录页面，而不是退出程序。
     * 选择“否”时保持当前连接和窗口不变。
     */
    private void requestQuit() {
        if (closed) {
            client.close();
            dispose();
            return;
        }
        int choice = JOptionPane.showConfirmDialog(
                this,
                "是否退出服务器并返回登录页面？\n退出后你将下线，但客户端会继续运行。",
                "退出服务器",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            quit();
        }
    }

    private void quit() {
        if (closed) {
            client.close();
            dispose();
            return;
        }
        closed = true;
        goOffline();
        dispose();
        DesktopLogin login = new DesktopLogin();
        login.setStatusMessage("已退出服务器，请重新登录");
        login.setVisible(true);
    }
}
