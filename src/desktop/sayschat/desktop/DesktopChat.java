package sayschat.desktop;

import sayschat.common.ClientHelp;
import sayschat.common.Json;
import sayschat.common.SaysClient;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final JLabel userCount = new JLabel("在线用户: 0");
    private final JLabel modeLabel = new JLabel(" ");
    private final SimpleAttributeSet stySys = new SimpleAttributeSet();
    private final SimpleAttributeSet styChat = new SimpleAttributeSet();
    private final SimpleAttributeSet stySelf = new SimpleAttributeSet();
    private final SimpleAttributeSet styTell = new SimpleAttributeSet();
    private final SimpleAttributeSet stySays = new SimpleAttributeSet();
    private final SimpleAttributeSet styHelp = new SimpleAttributeSet();

    private volatile boolean closed;
    /** 保证退出流程只执行一次，避免重复发送 quit。 */
    private final AtomicBoolean offline = new AtomicBoolean(false);

    public DesktopChat(SaysClient client) {
        super("SaysChatV4");
        this.client = client;
        this.me = client.user;
        this.admin = client.admin;
        this.allMuted = client.allMuted;
        for (Object o : client.users) {
            Map<String, Object> u = Json.obj(o);
            userAdmin.put(Json.str(u, "name"), Boolean.valueOf(Json.bool(u, "admin", false)));
        }
        buildUi();
        refreshUsers();
        updateTitle();
        updateMode();
        append(stySys, "欢迎 " + me + (admin ? "[管理员]" : "")
                + " 加入 SaysChatV4，连接已加密。");
        append(stySys, "输入 /help 查看命令。");
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
        userPanel.add(modeLabel, BorderLayout.SOUTH);

        input.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
        sendBtn.setPreferredSize(new Dimension(88, 34));
        JPanel inputRow = new JPanel(new BorderLayout(5, 0));
        inputRow.setBorder(new EmptyBorder(8, 8, 4, 8));
        inputRow.add(input, BorderLayout.CENTER);
        inputRow.add(sendBtn, BorderLayout.EAST);

        JLabel hint = new JLabel("所有用户: /tell 用户名 内容 私聊   |   管理员: /help 查看管理命令");
        hint.setForeground(new Color(0x888888));
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        hint.setBorder(new EmptyBorder(0, 10, 7, 10));

        JPanel south = new JPanel(new BorderLayout());
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
        setSize(920, 600);
        setMinimumSize(new Dimension(680, 440));
        setLocationRelativeTo(null);
    }

    private void updateTitle() {
        String suffix = admin ? "[管理员]" : "";
        setTitle("SaysChatV4 - " + me + suffix + (allMuted ? " · 全员禁言" : ""));
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

    private void handle(Map<String, Object> m) {
        if (closed) return;
        String t = Json.str(m, "t");
        if ("chat".equals(t)) {
            String from = Json.str(m, "from");
            boolean a = Json.bool(m, "admin", false);
            String who = from + (a ? "[管理员]" : "");
            append(from != null && from.equals(me) ? stySelf : styChat,
                    who + ": " + Json.str(m, "msg"));
        } else if ("tell".equals(t)) {
            String from = Json.str(m, "from");
            String to = Json.str(m, "to");
            String msg = Json.str(m, "msg");
            if (from != null && from.equals(me)) {
                append(styTell, "你对 " + to + " 私聊: " + msg);
            } else {
                append(styTell, from + " 对你私聊: " + msg);
            }
        } else if ("says".equals(t)) {
            append(stySays, "【公告】" + Json.str(m, "from") + ": " + Json.str(m, "msg"));
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
     * 点击窗口关闭按钮时先确认；只有用户选择“是”才退出服务器并关闭客户端。
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
                "是否退出服务器？\n退出后你将下线，并关闭 SaysChatV4 客户端。",
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
        System.exit(0);
    }
}
