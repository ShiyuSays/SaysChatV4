package sayschat.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import sayschat.common.ClientHelp;
import sayschat.common.FileTransfer;
import sayschat.common.Json;
import sayschat.common.SaysClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 * SaysChatV4.1.0 安卓客户端。
 * 单 Activity：登录/注册 <-> 聊天界面（消息列表 + 左侧折叠在线用户栏 + 输入栏）。
 * 所有网络操作在后台线程，消息回到主线程更新 UI。
 */
public class MainActivity extends Activity {
    private static final String TAG = "SaysChatV4.1.0";
    private static final int REQUEST_PICK_IMAGE = 1001;
    private static final int REQUEST_PICK_FILE = 1002;
    private static final int REQUEST_SAVE_ATTACHMENT = 1003;
    private static final int REQUEST_STORAGE_PERMISSION = 1004;
    private static final String SAVE_FOLDER = "SaysChatV4.1.0";
    private static final String PREF_FILE = "sayschat_connection";
    private static final String PREF_HOST = "host";
    private static final String PREF_PORT = "port";
    private static final String PREF_CONNECTION_PASSWORD = "connectionPassword";
    private static final String PREF_USERNAME = "username";
    private static final int MAX_IMAGE_SOURCE_BYTES = 25 * 1024 * 1024;
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
    private Button btnUsers, btnCloseUsers, btnSend, btnAttach, btnChangePwd;
    private EditText etInput;
    private ListView msgList, userList;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean alive = true;
    private volatile SaysClient pendingClient;
    private SaysClient conn;
    /** 所有 socket 写入都放到这个单线程队列中，绝不在安卓主线程做网络 I/O。 */
    private ExecutorService sendExecutor;
    /** 图片压缩、附件解码和保存等文件 I/O 后台队列。 */
    private ExecutorService fileExecutor;
    private String me;
    private boolean admin;
    private boolean allMuted;
    private boolean muted;
    private boolean panelOpen;
    private byte[] pendingSaveBytes;
    private String pendingSaveName;
    private String pendingSaveMime;

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
        btnAttach = (Button) findViewById(R.id.btnAttach);
        btnChangePwd = (Button) findViewById(R.id.btnChangePwd);
        etInput = (EditText) findViewById(R.id.etInput);
        msgList = (ListView) findViewById(R.id.msgList);
        userList = (ListView) findViewById(R.id.userList);

        msgAdapter = new MsgAdapter(this, messages);
        msgList.setAdapter(msgAdapter);
        userAdapter = new UserAdapter(this, userNames, userAdmin);
        userList.setAdapter(userAdapter);
        loadLastConnection();

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
        btnAttach.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAttachmentMenu();
            }
        });
        btnChangePwd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showChangePasswordDialog();
            }
        });
        msgList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= messages.size()) return;
                ChatMsg message = messages.get(position);
                if (message.attachment != null) {
                    requestSaveAttachment(message);
                }
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
        clearPendingSave();
        shutdownSender();
        shutdownFileExecutor();
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

    /** 恢复上次成功登录的服务器、端口、连接密码和用户名（账户密码不保存）。 */
    private void loadLastConnection() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE);
            String host = prefs.getString(PREF_HOST, "");
            String port = prefs.getString(PREF_PORT, "");
            String connectionPassword = prefs.getString(PREF_CONNECTION_PASSWORD, "");
            String username = prefs.getString(PREF_USERNAME, "");
            etHost.setText(host);
            if (!port.isEmpty()) etPort.setText(port);
            etConnPwd.setText(connectionPassword);
            etUser.setText(username);
            if (!TextUtils.isEmpty(host)) {
                tvLoginStatus.setText("已载入上次连接信息（账户密码需重新输入）");
            }
        } catch (Exception ignored) {
        }
    }

    /** 登录成功后保存连接信息，账户密码不写入本地。 */
    private void saveLastConnection(String host, int port,
                                    String connectionPassword, String username) {
        try {
            getSharedPreferences(PREF_FILE, MODE_PRIVATE).edit()
                    .putString(PREF_HOST, host)
                    .putString(PREF_PORT, String.valueOf(port))
                    .putString(PREF_CONNECTION_PASSWORD, connectionPassword)
                    .putString(PREF_USERNAME, username == null ? "" : username)
                    .commit();
        } catch (Exception ignored) {
        }
    }

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
                    saveLastConnection(host, port, connPwd, c.user);
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
        shutdownFileExecutor();
        sendExecutor = Executors.newSingleThreadExecutor(daemonFactory("sayschat-android-send"));
        fileExecutor = Executors.newSingleThreadExecutor(daemonFactory("sayschat-android-file"));
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
        addSys("欢迎 " + me + (admin ? "[管理员]" : "") + " 加入 SaysChatV4.1.0，连接已加密");
        if (allMuted) addSys("当前服务器处于全员禁言状态，管理员仍可发言");
        addSys("点左上角 ☰ 查看在线用户，/help 查看命令");
        addSys("点“附件”可发送图片或文件（≤"
                + (FileTransfer.MAX_FILE_BYTES / 1024 / 1024)
                + " MB），输入框文字作为附件说明；收到的图片自动保存到相册，文件自动保存到 Download");
        requestLegacyStoragePermission();
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
        if ("history".equals(type)) {
            handleHistory(m);
        } else if ("chat".equals(type)) {
            String from = Json.str(m, "from");
            boolean isAdmin = Json.bool(m, "admin", false);
            String who = from + (isAdmin ? "[管理员]" : "");
            String msg = Json.str(m, "msg");
            addMsg(from != null && from.equals(me) ? ChatMsg.KIND_SELF : ChatMsg.KIND_CHAT,
                    who, msg);
            if (from != null && !from.equals(me) && mentionsMe(msg)) {
                playNotificationSound();
            }
        } else if ("file".equals(type)) {
            receiveFileAttachment(m);
        } else if ("tell".equals(type)) {
            String from = Json.str(m, "from");
            String to = Json.str(m, "to");
            if (from != null && from.equals(me)) {
                addMsg(ChatMsg.KIND_TELL, "我 → " + to, Json.str(m, "msg"));
            } else {
                addMsg(ChatMsg.KIND_TELL, from + " → 我", Json.str(m, "msg"));
                playNotificationSound();
            }
        } else if ("says".equals(type)) {
            String from = Json.str(m, "from");
            addMsg(ChatMsg.KIND_SAYS, "【公告】" + from, Json.str(m, "msg"));
            if (from == null || !from.equals(me)) {
                playNotificationSound();
            }
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

    private void handleHistory(Map<String, Object> m) {
        Object raw = m.get("entries");
        if (!(raw instanceof List)) return;
        List<?> entries = (List<?>) raw;
        if (entries.isEmpty()) return;
        addSys("—— 服务器聊天记录（最近 " + entries.size() + " 条）——");
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
            int viewKind = -1;
            String header = null;
            String body = null;
            if ("chat".equals(kind)) {
                viewKind = from != null && from.equals(me)
                        ? ChatMsg.KIND_SELF : ChatMsg.KIND_CHAT;
                header = who;
                body = msg;
            } else if ("tell".equals(kind)) {
                String to = Json.str(entry, "to");
                viewKind = ChatMsg.KIND_TELL;
                header = from != null && from.equals(me)
                        ? "我 → " + to : (from == null ? "未知用户" : from) + " → 我";
                body = msg;
            } else if ("says".equals(kind)) {
                viewKind = ChatMsg.KIND_SAYS;
                header = "【公告】" + (from == null ? "服务器" : from);
                body = msg;
            } else if ("file".equals(kind)) {
                boolean image = "image".equals(Json.str(entry, "fileKind"));
                String name = Json.str(entry, "name");
                int size = intValue(entry.get("size"));
                StringBuilder text = new StringBuilder();
                text.append(name == null ? "attachment" : name)
                        .append(" (").append(formatBytes(size)).append(")");
                if (!msg.isEmpty()) text.append("\n").append(msg);
                text.append("  [历史附件]");
                viewKind = ChatMsg.KIND_FILE;
                header = who + " 的历史" + (image ? "图片" : "文件");
                body = text.toString();
            }
            if (viewKind < 0) continue;
            if (messages.size() >= 600) messages.remove(0);
            messages.add(ChatMsg.historical(viewKind, header, body, time));
        }
        addSys("—— 以上为服务器保存的历史记录 ——");
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

    /** 收到公告、私聊或公聊中被 @ 时播放系统通知音。 */
    private void playNotificationSound() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone ringtone = RingtoneManager.getRingtone(this, uri);
            if (ringtone != null) ringtone.play();
        } catch (Exception ignored) {
            // 设备没有通知音或音频不可用时不影响消息显示。
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

    private void disconnected(String reason) {
        if (conn == null) return;
        disconnectAndReset(reason == null ? "与服务器的连接已断开" : reason);
    }

    /** 断开连接并回到登录界面；关闭 socket 后服务器会立即把该用户下线。 */
    private void disconnectAndReset(String message) {
        SaysClient c = conn;
        conn = null;
        clearPendingSave();
        shutdownSender();
        shutdownFileExecutor();
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

    private void shutdownFileExecutor() {
        ExecutorService old = fileExecutor;
        fileExecutor = null;
        if (old != null) old.shutdownNow();
    }

    // ==================== 图片 / 文件 ====================

    /** Android 9 及以下写入公共 Pictures/Download 需要存储权限。 */
    private void requestLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION
                && (grantResults.length == 0
                || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            toast("未授予存储权限，自动保存可能失败，可点击附件消息手动另存");
        }
    }

    private void showAttachmentMenu() {
        if (conn == null) {
            toast("尚未连接服务器");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("发送图片或文件")
                .setItems(new String[]{"发送图片", "发送文件"},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                openAttachmentPicker(which == 0);
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 折叠用户栏底部的“修改密码”：校验两次输入后经加密连接提交服务器。 */
    private void showChangePasswordDialog() {
        if (conn == null) {
            toast("尚未连接服务器");
            return;
        }
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        box.setPadding(padding, dp(8), padding, 0);
        final EditText oldField = addPasswordField(box, "原密码");
        final EditText newField = addPasswordField(box, "新密码");
        final EditText confirmField = addPasswordField(box, "再次输入新密码");
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("修改密码")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface unused) {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                String oldPassword = oldField.getText().toString();
                                String newPassword = newField.getText().toString();
                                String confirmPassword = confirmField.getText().toString();
                                if (oldPassword.isEmpty()) {
                                    toast("请填写原密码");
                                    return;
                                }
                                if (newPassword.isEmpty()) {
                                    toast("请填写新密码");
                                    return;
                                }
                                if (newPassword.length() > 128) {
                                    toast("新密码最多 128 个字符");
                                    return;
                                }
                                if (!newPassword.equals(confirmPassword)) {
                                    toast("两次输入的新密码不一致");
                                    return;
                                }
                                if (conn == null) {
                                    toast("连接已断开");
                                    dialog.dismiss();
                                    return;
                                }
                                dialog.dismiss();
                                addSys("正在提交密码修改...");
                                send(map("t", "passwd",
                                        "old", oldPassword, "new", newPassword));
                            }
                        });
            }
        });
        dialog.show();
    }

    private EditText addPasswordField(LinearLayout parent, String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        parent.addView(field, params);
        return field;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void openAttachmentPicker(boolean image) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(image ? "image/*" : "*/*");
        try {
            startActivityForResult(Intent.createChooser(intent,
                            image ? "选择图片" : "选择文件"),
                    image ? REQUEST_PICK_IMAGE : REQUEST_PICK_FILE);
        } catch (ActivityNotFoundException e) {
            toast("没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SAVE_ATTACHMENT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                savePendingAttachment(data.getData());
            } else {
                clearPendingSave();
            }
            return;
        }
        if (resultCode == RESULT_OK && data != null && data.getData() != null
                && (requestCode == REQUEST_PICK_IMAGE || requestCode == REQUEST_PICK_FILE)) {
            startAttachmentUpload(data.getData(), requestCode == REQUEST_PICK_IMAGE);
        }
    }

    /** 读取、压缩并发送附件；所有文件 I/O 都在 background executor 中完成。 */
    private void startAttachmentUpload(final Uri uri, final boolean image) {
        final String caption = etInput.getText().toString().trim();
        if (caption.length() > FileTransfer.MAX_CAPTION_CHARS) {
            toast("说明文字最多 " + FileTransfer.MAX_CAPTION_CHARS + " 个字符");
            return;
        }
        final SaysClient session = conn;
        final ExecutorService executor = fileExecutor;
        if (session == null || executor == null) {
            toast("尚未连接服务器");
            return;
        }
        etInput.setText("");
        toast("正在处理" + (image ? "图片" : "文件") + "...");
        try {
            executor.execute(new Runnable() {
                public void run() {
                    try {
                        AttachmentUpload upload = readAttachment(uri, image);
                        final Map<String, Object> message = map(
                                "t", "file",
                                "kind", upload.kind,
                                "name", upload.name,
                                "mime", upload.mime,
                                "msg", caption,
                                "data", Base64.encodeToString(upload.bytes, Base64.NO_WRAP));
                        postIfAlive(new Runnable() {
                            public void run() {
                                if (conn != session) return;
                                send(message);
                                toast("附件已加入发送队列");
                            }
                        });
                    } catch (final Exception e) {
                        postIfAlive(new Runnable() {
                            public void run() {
                                if (conn == session) {
                                    toast("发送附件失败：" + errorText(e));
                                }
                            }
                        });
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            toast("发送失败：连接正在关闭");
        }
    }

    private AttachmentUpload readAttachment(Uri uri, boolean image) throws IOException {
        ContentResolver resolver = getContentResolver();
        String displayName = queryDisplayName(resolver, uri);
        if (image) {
            InputStream in = resolver.openInputStream(uri);
            if (in == null) throw new IOException("无法读取图片");
            byte[] source = readAll(in, MAX_IMAGE_SOURCE_BYTES);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(source, 0, source.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IOException("无法识别图片格式");
            }
            int sample = 1;
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeByteArray(source, 0, source.length, options);
            if (bitmap == null) throw new IOException("图片解码失败");
            if (bitmap.getWidth() > 1600 || bitmap.getHeight() > 1600) {
                float scale = Math.min(1600f / bitmap.getWidth(), 1600f / bitmap.getHeight());
                int w = Math.max(1, Math.round(bitmap.getWidth() * scale));
                int h = Math.max(1, Math.round(bitmap.getHeight() * scale));
                Bitmap resized = Bitmap.createScaledBitmap(bitmap, w, h, true);
                if (resized != bitmap) {
                    bitmap.recycle();
                    bitmap = resized;
                }
            }
            byte[] jpeg = null;
            try {
                for (int quality = 82; quality >= 50; quality -= 8) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                        throw new IOException("图片编码失败");
                    }
                    jpeg = out.toByteArray();
                    if (jpeg.length <= FileTransfer.MAX_FILE_BYTES) break;
                }
            } finally {
                bitmap.recycle();
            }
            if (jpeg == null || jpeg.length > FileTransfer.MAX_FILE_BYTES) {
                throw new IOException("图片压缩后仍超过 "
                        + (FileTransfer.MAX_FILE_BYTES / 1024 / 1024) + " MB");
            }
            String base = stripExtension(displayName);
            if (base.trim().isEmpty()) base = "image";
            return new AttachmentUpload(jpeg, safeFileName(base + ".jpg"),
                    "image/jpeg", "image");
        }

        long knownSize = querySize(resolver, uri);
        if (knownSize > FileTransfer.MAX_FILE_BYTES) {
            throw new IOException("文件超过 " + (FileTransfer.MAX_FILE_BYTES / 1024 / 1024)
                    + " MB 上限");
        }
        InputStream in = resolver.openInputStream(uri);
        if (in == null) throw new IOException("无法读取文件");
        byte[] bytes = readAll(in, FileTransfer.MAX_FILE_BYTES);
        if (bytes.length == 0) throw new IOException("文件为空");
        String mime = resolver.getType(uri);
        if (mime == null || mime.trim().isEmpty()) mime = "application/octet-stream";
        return new AttachmentUpload(bytes, safeFileName(displayName), mime, "file");
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > max) {
                    throw new IOException("附件超过 "
                            + (FileTransfer.MAX_FILE_BYTES / 1024 / 1024) + " MB 上限");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 接收图片/文件：后台解码并生成缩略图，点击消息可另存到用户选择的位置。 */
    private void receiveFileAttachment(final Map<String, Object> m) {
        final SaysClient session = conn;
        final ExecutorService executor = fileExecutor;
        if (session == null || executor == null) return;
        final String from = Json.str(m, "from");
        final boolean fromAdmin = Json.bool(m, "admin", false);
        final boolean image = "image".equals(Json.str(m, "kind"));
        final String name = safeFileName(Json.str(m, "name"));
        final String mime = Json.str(m, "mime");
        final String caption = Json.str(m, "msg") == null ? "" : Json.str(m, "msg");
        final String data = Json.str(m, "data");
        if (data == null || data.isEmpty()) return;
        try {
            executor.execute(new Runnable() {
                public void run() {
                    byte[] bytes;
                    try {
                        if (data.length() > FileTransfer.MAX_BASE64_CHARS + 8) {
                            throw new IllegalArgumentException("附件过大");
                        }
                        bytes = Base64.decode(data, Base64.DEFAULT);
                    } catch (Exception e) {
                        postIfAlive(new Runnable() {
                            public void run() {
                                if (conn == session) toast("收到损坏的附件数据");
                            }
                        });
                        return;
                    }
                    if (bytes.length == 0 || bytes.length > FileTransfer.MAX_FILE_BYTES) {
                        postIfAlive(new Runnable() {
                            public void run() {
                                if (conn == session) toast("收到不合法大小的附件");
                            }
                        });
                        return;
                    }
                    Bitmap thumbnail = image ? decodeThumbnail(bytes) : null;
                    String who = from == null ? "未知用户" : from;
                    if (fromAdmin) who += "[管理员]";
                    boolean mine = from != null && from.equals(me);
                    SaveResult saveResult = null;
                    if (!mine) {
                        saveResult = saveIncomingAttachment(bytes, name, mime, image);
                    }
                    String prefix;
                    if (mine) {
                        prefix = "你发送了";
                    } else {
                        prefix = who + " 发送了";
                    }
                    StringBuilder body = new StringBuilder();
                    body.append(prefix).append(image ? "图片" : "文件")
                            .append(": ").append(name)
                            .append(" (").append(formatBytes(bytes.length)).append(")");
                    byte[] clickBytes = null;
                    if (!mine) {
                        if (saveResult != null && saveResult.success) {
                            body.append("\n已保存到")
                                    .append(image ? "相册: " : ": ")
                                    .append(saveResult.location);
                        } else {
                            body.append("\n自动保存失败: ")
                                    .append(saveResult == null ? "未知错误" : saveResult.error)
                                    .append("\n点击此消息另存");
                            clickBytes = bytes;
                        }
                    }
                    if (!caption.isEmpty()) body.append("\n").append(caption);
                    final ChatMsg message = new ChatMsg(ChatMsg.KIND_FILE, who,
                            body.toString(), clickBytes, name,
                            mime == null ? "application/octet-stream" : mime,
                            image, thumbnail);
                    postIfAlive(new Runnable() {
                        public void run() {
                            if (conn != session) return;
                            addFileMessage(message);
                        }
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    /** 收到的图片自动进相册，其他文件自动进 Download；失败时回退为点击另存。 */
    private SaveResult saveIncomingAttachment(byte[] bytes, String name,
                                              String rawMime, boolean image) {
        String safeName = safeFileName(name);
        String mime = safeMime(rawMime, image);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return QMediaStore.save(getContentResolver(), bytes, safeName, mime, image);
            }
            return saveLegacyAttachment(bytes, safeName, mime, image);
        } catch (Exception e) {
            return SaveResult.failure(errorText(e));
        }
    }

    private SaveResult saveLegacyAttachment(byte[] bytes, String name,
                                            String mime, boolean image) throws IOException {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            return SaveResult.failure("未授予存储权限");
        }
        File base = Environment.getExternalStoragePublicDirectory(
                image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(base, SAVE_FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建保存目录");
        }
        File target = uniqueFile(dir, name);
        FileOutputStream out = new FileOutputStream(target);
        try {
            out.write(bytes);
            out.flush();
        } finally {
            out.close();
        }
        MediaScannerConnection.scanFile(this, new String[]{target.getAbsolutePath()},
                new String[]{mime}, null);
        String relative = image ? "Pictures/" : "Download/";
        return SaveResult.success(relative + SAVE_FOLDER + "/" + target.getName());
    }

    private static String safeMime(String mime, boolean image) {
        if (image) {
            return mime != null && mime.startsWith("image/") ? mime : "image/jpeg";
        }
        return mime == null || mime.trim().isEmpty() ? "application/octet-stream" : mime;
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

    private static final class SaveResult {
        final boolean success;
        final String location;
        final String error;

        private SaveResult(boolean success, String location, String error) {
            this.success = success;
            this.location = location;
            this.error = error;
        }

        static SaveResult success(String location) {
            return new SaveResult(true, location, null);
        }

        static SaveResult failure(String error) {
            return new SaveResult(false, null, error);
        }
    }

    /** Android 10+ 通过 MediaStore 保存，不需要存储权限。独立类避免低版本加载新 API。 */
    private static final class QMediaStore {
        static SaveResult save(ContentResolver resolver, byte[] bytes, String name,
                               String mime, boolean image) throws IOException {
            Uri collection;
            String relative;
            if (image) {
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                relative = Environment.DIRECTORY_PICTURES + "/" + SAVE_FOLDER;
            } else {
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                relative = Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_FOLDER;
            }
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(collection, values);
            if (uri == null) throw new IOException("无法创建媒体库记录");
            try {
                OutputStream out = resolver.openOutputStream(uri);
                if (out == null) throw new IOException("无法写入媒体库");
                try {
                    out.write(bytes);
                    out.flush();
                } finally {
                    out.close();
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
                return SaveResult.success(relative + "/" + name);
            } catch (IOException e) {
                resolver.delete(uri, null, null);
                throw e;
            } catch (RuntimeException e) {
                resolver.delete(uri, null, null);
                throw new IOException(errorText(e), e);
            }
        }
    }

    private static Bitmap decodeThumbnail(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / sample > 720 || bounds.outHeight / sample > 540) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) return null;
        if (bitmap.getWidth() > 720 || bitmap.getHeight() > 540) {
            float scale = Math.min(720f / bitmap.getWidth(), 540f / bitmap.getHeight());
            int w = Math.max(1, Math.round(bitmap.getWidth() * scale));
            int h = Math.max(1, Math.round(bitmap.getHeight() * scale));
            Bitmap resized = Bitmap.createScaledBitmap(bitmap, w, h, true);
            if (resized != bitmap) {
                bitmap.recycle();
                bitmap = resized;
            }
        }
        return bitmap;
    }

    private void addFileMessage(ChatMsg message) {
        if (messages.size() >= 600) messages.remove(0);
        messages.add(message);
        msgAdapter.notifyDataSetChanged();
        msgList.setSelection(messages.size() - 1);
    }

    private void requestSaveAttachment(ChatMsg message) {
        if (message.attachment == null) return;
        pendingSaveBytes = message.attachment;
        pendingSaveName = safeFileName(message.attachmentName);
        pendingSaveMime = message.attachmentMime == null
                ? "application/octet-stream" : message.attachmentMime;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(pendingSaveMime);
        intent.putExtra(Intent.EXTRA_TITLE, pendingSaveName);
        try {
            startActivityForResult(intent, REQUEST_SAVE_ATTACHMENT);
        } catch (ActivityNotFoundException e) {
            clearPendingSave();
            toast("没有可用的保存位置选择器");
        }
    }

    private void savePendingAttachment(final Uri uri) {
        final byte[] data = pendingSaveBytes;
        final String name = pendingSaveName;
        clearPendingSave();
        if (data == null) return;
        toast("正在保存" + (name == null ? "附件" : (" " + name)) + "...");
        Thread t = new Thread(new Runnable() {
            public void run() {
                boolean savedOk;
                String saveError;
                try {
                    OutputStream out = getContentResolver().openOutputStream(uri, "w");
                    if (out == null) throw new IOException("无法打开目标文件");
                    try {
                        out.write(data);
                        out.flush();
                    } finally {
                        out.close();
                    }
                    savedOk = true;
                    saveError = null;
                } catch (Exception e) {
                    savedOk = false;
                    saveError = errorText(e);
                }
                final boolean ok = savedOk;
                final String error = saveError;
                postIfAlive(new Runnable() {
                    public void run() {
                        if (ok) {
                            toast("附件已保存" + (name == null ? "" : "：" + name));
                        } else {
                            toast("保存失败：" + error);
                        }
                    }
                });
            }
        }, "sayschat-android-save");
        t.setDaemon(true);
        t.start();
    }

    private void clearPendingSave() {
        pendingSaveBytes = null;
        pendingSaveName = null;
        pendingSaveMime = null;
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null || fallback.trim().isEmpty() ? "attachment" : fallback;
    }

    private static long querySize(ContentResolver resolver, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1;
    }

    private static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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
            int dot = s.lastIndexOf('.');
            if (dot > 0 && s.length() - dot <= 16) {
                String ext = s.substring(dot);
                s = s.substring(0, FileTransfer.MAX_FILE_NAME_CHARS - ext.length()) + ext;
            } else {
                s = s.substring(0, FileTransfer.MAX_FILE_NAME_CHARS);
            }
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

    private static final class AttachmentUpload {
        final byte[] bytes;
        final String name;
        final String mime;
        final String kind;

        AttachmentUpload(byte[] bytes, String name, String mime, String kind) {
            this.bytes = bytes;
            this.name = name;
            this.mime = mime;
            this.kind = kind;
        }
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
            tvTitle.setText("SaysChatV4.1.0");
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
        tvSubtitle.setText("SaysChatV4.1.0 · " + state);
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
