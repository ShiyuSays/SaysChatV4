package sayschat.desktop;

import sayschat.common.SaysClient;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.Properties;

/**
 * 登录 / 注册窗口。
 * 输入服务器 IP 或域名、端口、连接密码（服务器配置文件中），以及用户名与账户密码。
 */
public final class DesktopLogin extends JFrame {
    private static final Color ACCENT = new Color(0x303F9F);
    private static final Color MUTED = new Color(0x777777);
    private static final String PREF_HOST = "host";
    private static final String PREF_PORT = "port";
    private static final String PREF_CONNECTION_PASSWORD = "connectionPassword";
    private static final String PREF_USERNAME = "username";
    private static final String SETTINGS_DIR = ".sayschat-v4.1.0";
    private static final String LEGACY_SETTINGS_DIR = ".sayschat-v4.0.1";
    private static final String SETTINGS_FILE = "connection.properties";
    private final JTextField hostField = new JTextField(18);
    private final JTextField portField = new JTextField("10001", 6);
    private final JPasswordField connPwdField = new JPasswordField();
    private final JTextField userField = new JTextField(18);
    private final JPasswordField pwdField = new JPasswordField();
    private final JCheckBox regBox = new JCheckBox("注册新账号（首次使用请勾选）");
    private final JButton connectBtn = new BlueButton("连接服务器");
    private final JLabel statusLabel = new JLabel("请输入服务器信息", SwingConstants.CENTER);

    public DesktopLogin() {
        super("SaysChatV4.1.0 - 登录");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        loadLastConnection();
        buildUi();
        pack();
        setLocationRelativeTo(null);
        setResizable(false);
    }

    /** 用于从聊天窗口返回登录页时显示提示。 */
    void setStatusMessage(String message) {
        if (message == null) return;
        statusLabel.setText(message);
        statusLabel.revalidate();
        statusLabel.repaint();
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Color.WHITE);
        root.setBorder(BorderFactory.createEmptyBorder(20, 28, 18, 28));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("SaysChatV4.1.0");
        title.setAlignmentX(CENTER_ALIGNMENT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 27f));
        title.setForeground(ACCENT);
        JLabel subtitle = new JLabel("加密聊天频道 · TCP · AES/HMAC");
        subtitle.setAlignmentX(CENTER_ALIGNMENT);
        subtitle.setForeground(MUTED);
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 12f));
        header.add(title);
        header.add(Box.createVerticalStrut(3));
        header.add(subtitle);
        header.add(Box.createVerticalStrut(16));
        root.add(header, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 4, 5, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        addRow(form, c, 0, "服务器 IP / 域名:", hostField);
        addRow(form, c, 1, "端口:", portField);
        addRow(form, c, 2, "连接密码:", connPwdField);
        addRow(form, c, 3, "用户名:", userField);
        addRow(form, c, 4, "账户密码:", pwdField);
        hostField.setToolTipText("请输入服务器 IP 或域名，例如 192.168.1.10 或 example.com");

        c.gridx = 0;
        c.gridy = 5;
        c.gridwidth = 2;
        regBox.setOpaque(false);
        form.add(regBox, c);

        JLabel hint = new JLabel("<html><span style='color:#777777'>连接密码见服务器 "
                + "sayschat-server.conf；注册只需勾选一次。<br>"
                + "自动记住上次服务器、连接密码和用户名（不保存账户密码）。</span></html>");
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        c.gridy = 6;
        form.add(hint, c);

        connectBtn.setPreferredSize(new Dimension(0, 38));
        c.gridy = 7;
        c.insets = new Insets(10, 4, 4, 4);
        form.add(connectBtn, c);

        statusLabel.setForeground(MUTED);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 12f));
        c.gridy = 8;
        c.insets = new Insets(2, 4, 2, 4);
        form.add(statusLabel, c);

        root.add(form, BorderLayout.CENTER);
        setContentPane(root);
        getRootPane().setDefaultButton(connectBtn);
        connectBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doConnect();
            }
        });
    }

    private void addRow(JPanel p, GridBagConstraints c, int y, String label, JComponent field) {
        c.gridwidth = 1;
        c.gridx = 0;
        c.gridy = y;
        c.weightx = 0;
        p.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        p.add(field, c);
    }

    private void doConnect() {
        final String host = hostField.getText().trim();
        final int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            statusLabel.setText("端口必须是数字");
            return;
        }
        if (port < 1 || port > 65535) {
            statusLabel.setText("端口必须在 1-65535 之间");
            return;
        }
        final String connPwd = new String(connPwdField.getPassword());
        final String user = userField.getText().trim();
        final String pwd = new String(pwdField.getPassword());
        final boolean register = regBox.isSelected();
        if (host.isEmpty()) {
            statusLabel.setText("请填写服务器 IP 或域名");
            return;
        }
        if (connPwd.isEmpty()) {
            statusLabel.setText("请填写连接密码（见服务器 sayschat-server.conf）");
            return;
        }
        if (user.isEmpty() || pwd.isEmpty()) {
            statusLabel.setText("请填写用户名和账户密码");
            return;
        }
        if (user.length() > 20) {
            statusLabel.setText("用户名最多 20 个字符");
            return;
        }
        connectBtn.setEnabled(false);
        statusLabel.setText("正在连接 " + host + ":" + port + " ...");
        new Thread(new Runnable() {
            public void run() {
                SaysClient client = null;
                try {
                    client = new SaysClient(host, port, connPwd);
                    client.login(user, pwd, register, "desktop");
                    saveLastConnection(host, port, connPwd, client.user);
                    final SaysClient ready = client;
                    client = null;
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            DesktopChat chat = new DesktopChat(ready);
                            chat.setVisible(true);
                            dispose();
                        }
                    });
                } catch (final SaysClient.AuthFailed e) {
                    if (client != null) client.close();
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            fail(e.getMessage());
                        }
                    });
                } catch (final Exception e) {
                    if (client != null) client.close();
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            fail("无法连接服务器: " + friendly(e));
                        }
                    });
                }
            }
        }, "connect").start();
    }

    private void fail(String msg) {
        connectBtn.setEnabled(true);
        statusLabel.setText("<html>" + escape(msg) + "</html>");
    }

    /** 载入上次成功登录时的服务器、端口、连接密码和用户名（不包含账户密码）。 */
    private void loadLastConnection() {
        File file = lastConnectionFile();
        migrateLegacySettings(file);
        if (!file.isFile()) return;
        Properties prefs = new Properties();
        Reader reader = null;
        try {
            reader = new InputStreamReader(new FileInputStream(file), "UTF-8");
            prefs.load(reader);
            String host = prefs.getProperty(PREF_HOST, "");
            String port = prefs.getProperty(PREF_PORT, "");
            String connectionPassword = prefs.getProperty(PREF_CONNECTION_PASSWORD, "");
            String username = prefs.getProperty(PREF_USERNAME, "");
            if (!host.isEmpty()) hostField.setText(host);
            if (!port.isEmpty()) portField.setText(port);
            if (!connectionPassword.isEmpty()) connPwdField.setText(connectionPassword);
            if (!username.isEmpty()) userField.setText(username);
            if (!host.isEmpty()) {
                statusLabel.setText("已载入上次连接信息（账户密码需重新输入）");
            }
        } catch (Exception ignored) {
            // 配置文件损坏或无权限时按首次使用处理。
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 成功登录后保存连接信息；账户密码永不写入本地。 */
    private static void saveLastConnection(String host, int port,
                                           String connectionPassword, String username) {
        Writer writer = null;
        try {
            File file = lastConnectionFile();
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            Properties prefs = new Properties();
            prefs.setProperty(PREF_HOST, host);
            prefs.setProperty(PREF_PORT, String.valueOf(port));
            prefs.setProperty(PREF_CONNECTION_PASSWORD, connectionPassword);
            prefs.setProperty(PREF_USERNAME, username == null ? "" : username);
            writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            prefs.store(writer,
                    "SaysChatV4.1.0 last connection; account password is NOT stored");
            writer.flush();
        } catch (Exception ignored) {
            // 保存失败不影响正常登录。
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static File lastConnectionFile() {
        File dir = new File(System.getProperty("user.home", "."), SETTINGS_DIR);
        return new File(dir, SETTINGS_FILE);
    }

    /** 从 4.0.1 的设置目录复制一次连接信息，避免升级后丢失。 */
    private static void migrateLegacySettings(File target) {
        if (target.isFile()) return;
        File legacy = new File(new File(System.getProperty("user.home", "."),
                LEGACY_SETTINGS_DIR), SETTINGS_FILE);
        if (!legacy.isFile()) return;
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(legacy);
            out = new FileOutputStream(target);
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            out.flush();
        } catch (Exception e) {
            try {
                target.delete();
            } catch (Exception ignored) {
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    static String friendly(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        String lower = m.toLowerCase();
        if (lower.contains("timed out") || lower.contains("timeout")) return "连接超时";
        if (lower.contains("connection refused") || lower.contains("refused")) {
            return "连接被拒绝（服务器未开启或端口错误）";
        }
        if (lower.contains("unknownhost") || lower.contains("failed to resolve")
                || lower.contains("no address")) {
            return "无法解析服务器地址";
        }
        if (lower.contains("network is unreachable")) return "网络不可达";
        return m;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
