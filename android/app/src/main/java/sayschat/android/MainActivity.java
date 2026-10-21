package sayschat.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import sayschat.common.ClientHelp;
import sayschat.common.Json;
import sayschat.common.SaysClient;

import java.util.ArrayList;
import java.util.Arrays;
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

/**
 * SaysChatV4 安卓客户端。
 * 单 Activity：登录/注册 <-> 聊天界面（消息列表 + 左侧折叠在线用户栏 + 输入栏）。
 * 所有网络操作在后台线程，消息回到主线程更新 UI。
 */
public class MainActivity extends Activity {
    private static final String TAG = "SaysChatV4";
    private static final Set<String> ADMIN_COMMANDS = new HashSet<String>(Arrays.asList(
            "kick", "ban", "unban", "ip", "says", "mute", "chat",
            "allmute", "allchat", "admin", "deadmin", "list"));

    // 登录界面
    private View loginView;
    private EditText etHost, etPort, etConnPwd, etUser, etPwd;
    private CheckBox cbRegister;
    private Button btnConnect;
    private TextView tvLoginStatus;

    // 聊天界面
    private View chatView, userPanel, userScrim;
    private TextView tvTitle, tvSubtitle, tvUserCount;
    private Button btnUsers, btnCloseUsers, btnSend;
    private EditText etInput;
    private ListView msgList, userList;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean alive = true;
    private volatile SaysClient pendingClient;
    private SaysClient conn;
    /** 所有 socket 写入都放到这个单线程队列中，绝不在安卓主线程做网络 I/O。 */
    private ExecutorService sendExecutor;
    private String me;
    private boolean admin;
    private boolean allMuted;
    private boolean muted;
    private boolean panelOpen;

    private final List<ChatMsg> messages = new ArrayList<ChatMsg>();
    private final List<String> userNames = new ArrayList<String>();
    private final Map<String, Boolean> userAdmin = new LinkedHashMap<String, Boolean>();
    private MsgAdapter msgAdapter;
    private UserAdapter userAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        loginView = findViewById(R.id.loginView);
        chatView = findViewById(R.id.chatView);
        userPanel = findViewById(R.id.userPanel);
        userScrim = findViewById(R.id.userScrim);
        etHost = (EditText) findViewById(R.id.etHost);
        etPort = (EditText) findViewById(R.id.etPort);
        etConnPwd = (EditText) findViewById(R.id.etConnPwd);
        etUser = (EditText) findViewById(R.id.etUser);
        etPwd = (EditText) findViewById(R.id.etPwd);
        cbRegister = (CheckBox) findViewById(R.id.cbRegister);
        btnConnect = (Button) findViewById(R.id.btnConnect);
        tvLoginStatus = (TextView) findViewById(R.id.tvLoginStatus);

        tvTitle = (TextView) findViewById(R.id.tvTitle);
        tvSubtitle = (TextView) findViewById(R.id.tvSubtitle);
        tvUserCount = (TextView) findViewById(R.id.tvUserCount);
        btnUsers = (Button) findViewById(R.id.btnUsers);
        btnCloseUsers = (Button) findViewById(R.id.btnCloseUsers);
        btnSend = (Button) findViewById(R.id.btnSend);
        etInput = (EditText) findViewById(R.id.etInput);
        msgList = (ListView) findViewById(R.id.msgList);
        userList = (ListView) findViewById(R.id.userList);

        msgAdapter = new MsgAdapter(this, messages);
        msgList.setAdapter(msgAdapter);
        userAdapter = new UserAdapter(this, userNames, userAdmin);
        userList.setAdapter(userAdapter);

        btnConnect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                connect();
            }
        });
        btnSend.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sendText();
            }
        });
        btnUsers.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                togglePanel();
            }
        });
        btnCloseUsers.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel();
            }
        });
        userScrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel();
            }
        });
        etInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    sendText();
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    protected void onDestroy() {
        alive = false;
        ui.removeCallbacksAndMessages(null);
        shutdownSender();
        SaysClient pending = pendingClient;
        pendingClient = null;
        if (pending != null) pending.close();
        SaysClient c = conn;
        conn = null;
        if (c != null) c.close();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (chatView.getVisibility() == View.VISIBLE) {
            if (panelOpen) {
                closePanel();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("断开连接")
                    .setMessage("确定要退出聊天频道吗？")
                    .setNegativeButton("继续聊天", null)
                    .setPositiveButton("断开", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            disconnectAndReset("已断开连接");
                        }
                    })
                    .show();
            return;
        }
        super.onBackPressed();
    }

    // ==================== 登录 ====================

    private void connect() {
        final String host = etHost.getText().toString().trim();
        final int port;
        try {
            String p = etPort.getText().toString().trim();
            port = p.isEmpty() ? 10001 : Integer.parseInt(p);
        } catch (NumberFormatException e) {
            toast("端口必须是数字");
            return;
        }
        if (port < 1 || port > 65535) {
            toast("端口必须在 1-65535 之间");
            return;
        }
        final String connPwd = etConnPwd.getText().toString();
        final String user = etUser.getText().toString().trim();
        final String pwd = etPwd.getText().toString();
        final boolean register = cbRegister.isChecked();
        if (TextUtils.isEmpty(host)) {
            toast("请填写服务器 IP 或域名");
            return;
        }
        if (TextUtils.isEmpty(connPwd)) {
            toast("请填写连接密码（见服务器 sayschat-server.conf）");
            return;
        }
        if (TextUtils.isEmpty(user) || TextUtils.isEmpty(pwd)) {
            toast("请填写用户名和账户密码");
            return;
        }
        if (user.length() > 20) {
            toast("用户名最多 20 个字符");
            return;
        }
        hideKeyboard();
        btnConnect.setEnabled(false);
        tvLoginStatus.setText("正在连接 " + host + ":" + port + " ...");
        
        new Thread(new Runnable() {
            public void run() {
                SaysClient c = null;
                try {
                    c = new SaysClient(host, port, connPwd);
                    c.login(user, pwd, register, "android");
                    final SaysClient ready = c;
                    pendingClient = ready;
                    if (!alive) { pendingClient = null; ready.close(); return; }
                    c = null;
                    postIfAlive(new Runnable() {
                        public void run() {
                            onConnected(ready);
                        }
                    });
                } catch (final SaysClient.AuthFailed e) {
                    if (c != null) c.close();
                    postIfAlive(new Runnable() {
                        public void run() {
                            loginFail(e.getMessage());
                        }
                    });
                } catch (final Exception e) {
                    if (c != null) c.close();
                    postIfAlive(new Runnable() {
                        public void run() {
                            loginFail("无法连接服务器：" + friendly(e));
                        }
                    });
                }
            }
        }, "sayschat-connect").start();
    }

    private void postIfAlive(final Runnable r) {
        ui.post(new Runnable() {
            public void run() {
                if (alive) r.run();
            }
        });
    }

    private void loginFail(String message) {
        btnConnect.setEnabled(true);
        tvLoginStatus.setText(message == null ? "" : message);
        toast(message == null ? "连接失败" : message);
    }

    private void onConnected(SaysClient c) {
        if (!alive) {
            c.close();
            return;
        }
        if (pendingClient == c) pendingClient = null;
        shutdownSender();
        sendExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "sayschat-android-send");
                t.setDaemon(true);
                return t;
            }
        });
        conn = c;
        me = c.user;
        admin = c.admin;
        allMuted = c.allMuted;
        muted = false;
        userAdmin.clear();
        for (Object o : c.users) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> u = Json.obj(o);
            userAdmin.put(Json.str(u, "name"), Boolean.valueOf(Json.bool(u, "admin", false)));
        }
        messages.clear();
        msgAdapter.notifyDataSetChanged();
        refreshUsers();
        loginView.setVisibility(View.GONE);
        chatView.setVisibility(View.VISIBLE);
        closePanel();
        updateHeader();
        tvLoginStatus.setText("");
        etPwd.setText("");
        addSys("欢迎 " + me + (admin ? "[管理员]" : "") + " 加入 SaysChatV4，连接已加密");
        if (allMuted) addSys("当前服务器处于全员禁言状态，管理员仍可发言");
        addSys("点左上角 ☰ 查看在线用户，/help 查看命令");
        c.startReader(new SaysClient.Callback() {
            public void onMessage(final Map<String, Object> m) {
                postIfAlive(new Runnable() {
                    public void run() {
                        handle(m);
                    }
                });
            }

            public void onDisconnected(final String reason) {
                postIfAlive(new Runnable() {
                    public void run() {
                        disconnected(reason);
                    }
                });
            }
        });
    }

    // ==================== 消息处理 ====================

    private void handle(Map<String, Object> m) {
        if (conn == null) return;
        String type = Json.str(m, "t");
        if ("chat".equals(type)) {
            String from = Json.str(m, "from");
            boolean isAdmin = Json.bool(m, "admin", false);
            String who = from + (isAdmin ? "[管理员]" : "");
            addMsg(from != null && from.equals(me) ? ChatMsg.KIND_SELF : ChatMsg.KIND_CHAT,
                    who, Json.str(m, "msg"));
        } else if ("tell".equals(type)) {
            String from = Json.str(m, "from");
            String to = Json.str(m, "to");
            if (from != null && from.equals(me)) {
                addMsg(ChatMsg.KIND_TELL, "我 → " + to, Json.str(m, "msg"));
            } else {
                addMsg(ChatMsg.KIND_TELL, from + " → 我", Json.str(m, "msg"));
            }
        } else if ("says".equals(type)) {
            addMsg(ChatMsg.KIND_SAYS, "【公告】" + Json.str(m, "from"), Json.str(m, "msg"));
        } else if ("sys".equals(type)) {
            addSys(Json.str(m, "msg"));
        } else if ("join".equals(type)) {
            String name = Json.str(m, "name");
            boolean isAdmin = Json.bool(m, "admin", false);
            userAdmin.put(name, Boolean.valueOf(isAdmin));
            refreshUsers();
            addSys(name + (isAdmin ? "[管理员]" : "") + " 加入了聊天");
        } else if ("leave".equals(type)) {
            String name = Json.str(m, "name");
            userAdmin.remove(name);
            refreshUsers();
            addSys(name + " 离开了聊天");
        } else if ("users".equals(type)) {
            userAdmin.clear();
            Object ul = m.get("users");
            if (ul instanceof List) {
                for (Object o : (List<?>) ul) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> u = Json.obj(o);
                    userAdmin.put(Json.str(u, "name"), Boolean.valueOf(Json.bool(u, "admin", false)));
                }
            }
            refreshUsers();
        } else if ("role".equals(type)) {
            admin = Json.bool(m, "admin", false);
            conn.admin = admin;
            updateHeader();
            addSys(admin ? "您已被授予管理员权限" : "您已被撤销管理员权限");
        } else if ("allmute".equals(type)) {
            allMuted = Json.bool(m, "muted", false);
            conn.allMuted = allMuted;
            updateHeader();
            String by = Json.str(m, "by");
            addSys((by == null ? "管理员" : by)
                    + (allMuted ? " 开启了全员禁言（管理员仍可发言）"
                    : " 解除了全员禁言"));
        } else if ("mute".equals(type)) {
            muted = Json.bool(m, "muted", false);
            updateHeader();
            addSys(muted ? "您已被管理员禁言" : "您已被解除禁言");
        } else if ("kicked".equals(type)) {
            String reason = Json.str(m, "reason");
            disconnectAndReset("您已被移出：" + (reason == null ? "" : reason));
        }
    }

    private void disconnected(String reason) {
        if (conn == null) return;
        disconnectAndReset(reason == null ? "与服务器的连接已断开" : reason);
    }

    /** 断开连接并回到登录界面；关闭 socket 后服务器会立即把该用户下线。 */
    private void disconnectAndReset(String message) {
        SaysClient c = conn;
        conn = null;
        shutdownSender();
        if (c != null) c.close();
        closePanel();
        chatView.setVisibility(View.GONE);
        loginView.setVisibility(View.VISIBLE);
        btnConnect.setEnabled(true);
        tvLoginStatus.setText(message == null ? "" : message);
        if (!TextUtils.isEmpty(message)) toast(message);
        etPwd.setText("");
        messages.clear();
        msgAdapter.notifyDataSetChanged();
        userAdmin.clear();
        userNames.clear();
        userAdapter.notifyDataSetChanged();
        tvUserCount.setText("在线用户: 0");
        me = null;
        admin = false;
        allMuted = false;
        muted = false;
        updateHeader();
    }

    private void shutdownSender() {
        ExecutorService old = sendExecutor;
        sendExecutor = null;
        if (old != null) old.shutdownNow();
    }

    // ==================== 发送 ====================

    private void sendText() {
        if (conn == null) {
            toast("尚未连接服务器");
            return;
        }
        String text = etInput.getText().toString().trim();
        if (text.isEmpty()) return;
        etInput.setText("");
        if (text.startsWith("/")) {
            handleCommand(text);
        } else {
            send(map("t", "chat", "msg", text));
        }
    }

    private void handleCommand(String line) {
        String body = line.substring(1).trim();
        int sp = body.indexOf(' ');
        String cmd = (sp < 0 ? body : body.substring(0, sp)).toLowerCase(Locale.ENGLISH);
        String arg = sp < 0 ? "" : body.substring(sp + 1).trim();

        if ("tell".equals(cmd)) {
            int sp2 = arg.indexOf(' ');
            String to = sp2 < 0 ? arg : arg.substring(0, sp2);
            String msg = sp2 < 0 ? "" : arg.substring(sp2 + 1).trim();
            if (to.isEmpty() || msg.isEmpty()) {
                addSys("用法: /tell 用户名 私聊内容");
                return;
            }
            send(map("t", "tell", "to", to, "msg", msg));
            return;
        }
        if ("help".equals(cmd) || "?".equals(cmd)) {
            addHelp(ClientHelp.text());
            return;
        }
        if ("stop".equals(cmd)) {
            addSys("/stop 仅服务器控制台可用");
            return;
        }
        if (ADMIN_COMMANDS.contains(cmd)) {
            if (!admin) {
                addSys("该命令仅管理员或服务器端可用");
                return;
            }
            boolean noArg = "allmute".equals(cmd) || "allchat".equals(cmd) || "list".equals(cmd);
            if (arg.isEmpty() && !noArg) {
                addSys("says".equals(cmd)
                        ? "用法: /says 公告内容" : "用法: /" + cmd + " 用户名");
                return;
            }
            send(map("t", "cmd", "cmd", cmd, "arg", arg));
            return;
        }
        addSys("未知命令 /" + cmd + "，输入 /help 查看命令");
    }

    /**
     * 在后台单线程队列中发送，避免 Android 主线程网络 I/O
     * （否则会抛 NetworkOnMainThreadException，其 getMessage() 为 null，
     * 旧版本界面因此显示“发送失败：null”）。
     */
    private void send(final Map<String, Object> m) {
        final SaysClient c = conn;
        final ExecutorService executor = sendExecutor;
        if (c == null || executor == null) {
            addSys("发送失败：连接已断开");
            return;
        }
        try {
            executor.execute(new Runnable() {
                public void run() {
                    try {
                        c.send(m);
                    } catch (final Exception e) {
                        Log.w(TAG, "send failed", e);
                        postIfAlive(new Runnable() {
                            public void run() {
                                if (conn != c) return;
                                String detail = friendly(e);
                                disconnectAndReset("发送失败，网络连接已断开："
                                        + detail + "。请检查移动数据或服务器状态后重新连接");
                            }
                        });
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            addSys("发送失败：连接正在关闭");
        }
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ==================== 界面 ====================

    private void updateHeader() {
        if (me == null) {
            tvTitle.setText("SaysChatV4");
            tvSubtitle.setText("公共频道 · 已加密");
            return;
        }
        tvTitle.setText(me + (admin ? "[管理员]" : ""));
        String state;
        if (muted) {
            state = "您已被禁言";
        } else if (allMuted) {
            state = admin ? "全员禁言中 · 管理员可发言" : "全员禁言中";
        } else {
            state = "公共频道 · 已加密";
        }
        tvSubtitle.setText("SaysChatV4 · " + state);
        int color = (muted || allMuted)
                ? getResources().getColor(R.color.danger)
                : getResources().getColor(R.color.text_hint);
        tvSubtitle.setTextColor(color);
    }

    private void togglePanel() {
        if (panelOpen) {
            closePanel();
        } else {
            panelOpen = true;
            userPanel.setVisibility(View.VISIBLE);
            userScrim.setVisibility(View.VISIBLE);
        }
    }

    private void closePanel() {
        panelOpen = false;
        userPanel.setVisibility(View.GONE);
        userScrim.setVisibility(View.GONE);
    }

    private void addMsg(int kind, String header, String body) {
        if (messages.size() >= 600) {
            messages.remove(0);
        }
        messages.add(new ChatMsg(kind, header, body == null ? "" : body));
        msgAdapter.notifyDataSetChanged();
        msgList.setSelection(messages.size() - 1);
    }

    private void addSys(String text) {
        addMsg(ChatMsg.KIND_SYS, null, text);
    }

    private void addHelp(String text) {
        addMsg(ChatMsg.KIND_HELP, null, text);
    }

    private void refreshUsers() {
        userNames.clear();
        for (Map.Entry<String, Boolean> e : userAdmin.entrySet()) {
            if (e.getKey() != null) userNames.add(e.getKey());
        }
        userAdapter.notifyDataSetChanged();
        tvUserCount.setText("在线用户: " + userNames.size());
        if (userNames.isEmpty()) {
            tvUserCount.setText("在线用户: 0");
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(etInput.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    private void toast(String message) {
        if (message == null || message.isEmpty()) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String friendly(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        String lower = m.toLowerCase(Locale.ENGLISH);
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
}
