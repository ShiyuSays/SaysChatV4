# SaysChatV4 加密聊天频道

浪费Token使用DeepSeek v4.1 Flash Max写的

SaysChatV4 是一个基于 **TCP** 的加密聊天频道，由 **服务器端 + 电脑客户端（Swing）+ 安卓客户端** 三部分组成。
服务器端与电脑客户端使用纯 **Java 8** 编写，安卓端同样使用 Java 8 语法和 Android 平台 API，无任何第三方运行时依赖。

- 服务端默认监听 **TCP 10001**
- 首次启动自动在当前工作目录生成 `sayschat-server.conf`（端口 + 连接密码 + 最大在线人数）
- 客户端可注册用户名并设置账户密码，服务器持久化保存账户
- 在线用户列表实时更新：电脑端在**右侧**，安卓端在**左侧折叠面板**
- 管理员在用户列表中显示为 `用户名[管理员]`

## 一、功能一览

| 功能 | 说明 |
| --- | --- |
| 注册 / 登录 | 客户端填写服务器 IP/域名、端口、连接密码、用户名、账户密码；首次使用勾选注册 |
| 加密传输 | 连接密码派生会话密钥，AES-128-CBC + HMAC-SHA256（encrypt-then-MAC） |
| 密码保护 | 账户密码只保存 PBKDF2 加盐哈希，服务器不保存账户密码明文 |
| 公聊 / 私聊 | 公聊广播；所有登录用户都可使用 `/tell 用户名 内容` 私聊 |
| 在线用户栏 | 电脑端右侧固定栏、安卓端左侧折叠栏；实时显示加入、离开和权限变化 |
| 人数上限 | `maxOnline` 配置最大同时在线人数，满员时拒绝新登录，有人下线后自动释放名额 |
| 管理员头衔 | 用户栏与聊天消息中均显示 `用户名[管理员]` |
| 管理命令 | 踢人、封禁/解封、查 IP、公告、单人禁言/解禁、全员禁言/解禁、授予/撤销管理员 |
| 服务器控制台 | 管理员命令全部可用；另有 `/stop` 关闭服务器、`/list` 查看在线列表 |
| 数据持久化 | 账户、密码哈希、管理员标记、封禁标记保存到 `sayschat-accounts.dat` |
| 跨平台 | 服务端/桌面端 jar 可运行于 Windows、Linux、macOS；安卓 APK 最低 Android 5.0（API 21） |

## 二、目录与构建产物

```
SaysChat/
├── src/
│   ├── common/      协议、JSON、AES/HMAC 加密、客户端连接层（桌面/安卓共用）
│   ├── server/      服务器端
│   └── desktop/     电脑客户端（Swing）
├── android/         安卓工程（可用 Android Studio 打开）
├── tools/           开发测试工具（SmokeTest / Probe / SeedAdmin）
├── dist/
│   ├── SaysChatV4-Server.jar     服务器端
│   ├── SaysChatV4-Desktop.jar    电脑客户端
│   ├── SaysChatV4.apk            安卓客户端（debug 签名，可直接安装）
│   └── SHA256SUMS.txt            构建产物 SHA-256 校验值
├── build.bat / build.sh           构建两个 jar
├── build-apk.bat / build-apk.sh   无需 Gradle 构建 APK
├── start-server.sh                Linux 前台启动
├── start-server-daemon.sh         Linux 后台启动（日志 logs/server.log）
└── stop-server.sh                 Linux 停止后台服务器
```

## 三、快速开始

### 1. 启动服务器

```bash
java -jar dist/SaysChatV4-Server.jar
```

首次启动会在**当前工作目录**生成：

```ini
port=10001
password=首次启动随机生成的连接密码
maxOnline=100
```

控制台会同时打印端口、连接密码和最大同时在线人数。把连接密码告知客户端用户，然后启动客户端。再次启动直接读取该文件，修改后重启生效。

- `maxOnline`：最大**同时在线**人数，范围 1-10000，默认 100。达到上限后新登录会被拒绝（提示“服务器在线人数已满”）；已在线的用户不受影响，有人下线后名额自动释放。
- 旧配置文件中没有 `maxOnline` 时，服务器使用默认值 100，并在启动日志中提示。
- 该限制针对同时在线连接，不限制历史注册账户总数。

> 配置文件与账户文件生成在“工作目录”，不是 jar 文件所在目录。Linux 启动脚本会先切换到脚本所在目录。

### 2. 第一个管理员

服务器启动后：

1. 用客户端注册一个账户并登录；
2. 在服务器控制台执行：

```text
/admin 用户名
```

该账户立即获得管理员权限，无需重新登录；客户端会收到角色更新，用户栏显示 `用户名[管理员]`。

如果使用后台方式启动、没有可交互控制台，可先在有控制台的前台模式下完成首次注册和 `/admin`，再改用后台脚本；或在服务器停止时用 `tools/SeedAdmin` 预置管理员（见“测试工具”）。

### 3. 运行电脑客户端

```bash
java -jar dist/SaysChatV4-Desktop.jar
```

填写：

- 服务器 IP / 域名
- 端口（默认 10001）
- 连接密码（服务器 `sayschat-server.conf` 中的 password）
- 用户名、账户密码
- 首次注册勾选“注册新账号”

登录后：中间为聊天记录，**右侧为在线用户栏**，底部输入框发送消息，`Enter` 发送。

关闭电脑客户端窗口时会发送 `quit` 并立即关闭 TCP 连接，服务器随即把该用户下线；JVM 退出时还有关闭连接的兜底处理。

### 4. 安装安卓客户端

安装 `dist/SaysChatV4.apk`（debug 签名，需要在系统中允许安装未知来源应用）。

- 输入服务器 IP / 域名和端口
- 点击左上角 **☰ 在线** 展开/收起**左侧在线用户栏**
- 点击用户栏右上角 `×` 或列表外遮罩也可收起
- 管理器头衔显示为 `用户名[管理员]`

物理手机需填写电脑/服务器的局域网 IP，例如 `192.168.1.10`；Android 模拟器访问宿主机使用 `10.0.2.2`。服务器防火墙需放行 TCP 10001：

```bash
sudo ufw allow 10001/tcp
# 或 firewalld:
sudo firewall-cmd --add-port=10001/tcp --permanent && sudo firewall-cmd --reload
```

## 四、命令

### 所有登录用户

| 命令 | 说明 |
| --- | --- |
| `/tell 用户名 私聊内容` | 给在线用户发送私聊；发送者会看到回显，其他用户不可见 |
| `/help` | 查看命令帮助 |

### 管理员与服务器控制台

| 命令 | 说明 |
| --- | --- |
| `/kick 用户名` | 踢出在线用户（可重新登录） |
| `/ban 用户名` | 封禁账户并断开在线连接；封禁状态持久化，登录会被拒绝 |
| `/unban 用户名` | 解除封禁 |
| `/ip 用户名` | 查看在线用户 IP、来源端口和设备类型 |
| `/says 内容` | 向所有在线用户广播红色公告 |
| `/mute 用户名` | 禁言某用户（仅禁止公聊，私聊不受影响；会话级） |
| `/chat 用户名` | 解除该用户的单人禁言 |
| `/allmute` | 全员禁言；管理员仍可公聊 |
| `/allchat` | 解除全员禁言 |
| `/admin 用户名` | 授予管理员权限（持久化，立即生效） |
| `/deadmin 用户名` | 撤销管理员权限（附加命令） |
| `/list` | 查看在线用户、管理员标记、禁言标记（附加命令） |

### 仅服务器控制台

| 命令 | 说明 |
| --- | --- |
| `/stop` | 通知在线用户并关闭服务器 |
| `/help` | 控制台命令帮助 |

说明：

- 服务器控制台的 `/help` 使用**绿色竖列清单**输出，每条命令单独一行。
- 电脑客户端和安卓客户端的 `/help` 同样使用**绿色竖列清单**输出。
- 权限以**服务端校验**为准，即使伪造客户端命令也无法越权。
- 管理员不能通过客户端 `/ban` 另一个管理员；服务器控制台可以。
- `/mute` 与 `/allmute` 只拦截公聊；`/tell` 私聊不受禁言影响。
- `/allmute` 状态下 `admin=true` 的用户仍可公聊；撤销管理员后立即失去该豁免。
- 被踢出/封禁的客户端会收到原因并返回登录界面。

## 五、Linux 服务器脚本

先把整个目录上传到服务器，赋予执行权限：

```bash
chmod +x start-server.sh start-server-daemon.sh stop-server.sh
```

前台运行（推荐首次使用，方便设置第一个管理员）：

```bash
./start-server.sh
```

后台运行：

```bash
./start-server-daemon.sh
tail -f logs/server.log       # 查看日志；首次启动的连接密码也在日志里
./stop-server.sh              # 停止服务器
```

后台脚本会写入 `server.pid`；停止脚本会先发送 `SIGTERM` 让服务器优雅关闭，超时后才强制结束。

服务器需要 Java 8 或更新版本。Debian/Ubuntu 可安装：

```bash
sudo apt update
sudo apt install openjdk-8-jre-headless
```

## 六、重新构建

### 构建两个 jar

要求 JDK 8+（`javac`、`jar` 在 PATH 中）：

```bash
# Linux / macOS
./build.sh

# Windows
build.bat
```

产物：

- `dist/SaysChatV4-Server.jar`
- `dist/SaysChatV4-Desktop.jar`

### 构建安卓 APK（无需 Gradle）

需要 Android SDK，包含 `build-tools` 和至少一个 `platforms/android-*`。

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
./build-apk.sh
```

Windows：

```bat
set ANDROID_SDK_ROOT=C:\path\to\android-sdk
build-apk.bat
```

说明：

- 构建脚本自动选择最新 build-tools/platform，最低 SDK 21、target SDK 30、versionCode 4、versionName 4.0。
- **build-tools 31+ 的 `d8`/`apksigner` 需要 JDK 11+**；脚本会在常见路径查找 JDK 17，也可设置 `JAVA17_HOME`：
  ```bat
  set JAVA17_HOME=C:\Program Files\Java\jdk-17
  build-apk.bat
  ```
- 使用固定调试签名 `android/signing/debug.keystore`（storepass/keypass 均为 `android`），后续重建 APK 可以覆盖安装。此前的 APK 若由另一把临时调试密钥签名，需要先卸载旧版，再安装本版。
- 正式发布请换成自己的 release 密钥并妥善备份，不要继续使用仓库内的公共调试密钥。
- 也可用 Android Studio 打开 `android/` 目录构建；Gradle 配置已经包含共用源码目录 `src/common`。

构建产物校验：

```bash
cd dist
sha256sum -c SHA256SUMS.txt        # Windows: certutil -hashfile <文件> SHA256
```

## 七、加密与协议

```text
1. 客户端 TCP 连接 10001
2. 服务器发送明文头: "SAYS4"(5B) + 随机盐(16B) + PBKDF2迭代次数(4B)
3. 双方以 PBKDF2-HMAC-SHA1(连接密码, 盐, 120000) 派生 32B 会话密钥
4. 后续每一帧: [4B大端长度][IV(16B) + AES-128-CBC密文 + HMAC-SHA256(32B)]
5. 服务器先校验 HMAC，再解密；所有业务消息均为 JSON
6. 连接密码错误时，服务器返回明文帧 "BADPASS" 后断开
```

账户密码：

- 随机 16 字节盐
- PBKDF2-HMAC-SHA1 120000 次，输出 32 字节
- 数据文件只存盐与哈希，不含明文密码

数据文件：

- `sayschat-server.conf`：端口、连接密码、最大同时在线人数（Linux 下权限收紧为 600）
- `sayschat-accounts.dat`：用户名、盐、密码哈希、管理员/封禁标记（JSON，UTF-8）

## 八、安全说明与限制

- 这是**传输加密 + 服务器端存储保护**，不是端到端加密（E2EE）。服务器需要解密消息进行广播和私聊转发，服务器管理员能看到在线消息和日志。
- 连接密码同时是会话密钥的派生来源，必须通过安全渠道告知用户；泄露后应修改配置并重启服务器。
- 协议没有证书/公钥体系，无法抵御能够主动中间人劫持并持有连接密码的攻击者；不要穿过不可信代理使用。
- 会话密钥无前向保密（每次连接使用新的随机盐，但没有 DH 密钥交换）。
- 未实现消息历史云端保存、离线消息、群组、文件传输等；重启服务器会清空在线状态和禁言状态，账户/管理员/封禁状态保留。
- 提供的 APK 使用 debug 签名，仅适合内测和自用；分发前建议替换 release 签名。
- 服务器日志会记录聊天/私聊内容，部署时请控制日志文件访问权限。

## 九、测试工具（开发用）

`tools/` 中提供了三个无第三方依赖的小工具：

```bash
# 编译（Windows 用 ; 分隔 classpath，Linux/macOS 用 :）
javac -encoding UTF-8 -cp dist/SaysChatV4-Server.jar -d tools/classes tools/*.java
```

- **SeedAdmin**：服务器停止时预置管理员账户，适合无控制台场景。
  ```bash
  java -cp "dist/SaysChatV4-Server.jar;tools/classes" \
       sayschat.tools.SeedAdmin . admin admin-pass-123
  ```
- **Probe**：登录一次并输出角色/全员禁言/在线列表，用于重启持久化验证。
- **SmokeTest**：端到端测试（认证失败、公聊、私聊、公告、禁言、全员禁言、权限授予、踢人、封禁/解封、IP、客户端 `/stop` 拒绝等 30+ 项断言）。使用前需要干净的服务端数据目录、已存在的管理员账户，且 bob/carol/dave 未注册：
  ```bash
  java -cp "dist/SaysChatV4-Server.jar;tools/classes" \
       sayschat.tools.SmokeTest 127.0.0.1 10001 <连接密码> admin admin-pass-123
  ```

## 十、常见问题

**连接被拒绝 / 连接超时**
：确认服务器已启动、端口为 10001，并已放行防火墙 TCP 10001；安卓模拟器访问宿主机请用 `10.0.2.2`。

**连接密码错误**
：与服务器工作目录 `sayschat-server.conf` 中 `password=` 后内容逐字一致（区分大小写）。

**该用户已在线**
：同一用户名同时只允许一个客户端连接，请先关闭旧连接。

**提示服务器在线人数已满**
：已达到 `sayschat-server.conf` 中 `maxOnline` 配置的同时在线上限；等待其他用户下线，或修改该值并重启服务器。

**安卓端发送消息失败**
：先确认手机移动数据能访问服务器 TCP 10001 端口。安卓端发送已放到后台单线程执行，断线时会显示明确的网络原因并返回登录页，不再出现 `发送失败：null`；重新登录即可继续。

**登录提示被封禁**
：管理员执行 `/unban 用户名` 后才能重新登录。

**禁言后重新登录为什么能发言**
：单人禁言和全员禁言都是服务器运行期间的会话状态，不写入账户文件；服务器重启或用户重新登录后单人禁言会解除。封禁和管理员权限才会持久化。

**改了连接密码后所有人都断开**
：连接密码参与会话密钥派生，修改配置并重启后，旧连接自动失效，客户端需用新密码重连。

**忘记连接密码**
：直接查看服务器工作目录的 `sayschat-server.conf`；如果删除该文件重启服务器，会生成新的随机密码，但账户文件仍会保留。

**服务器关不掉**
：前台模式可在控制台输入 `/stop`，或按 `Ctrl+C`；后台模式执行 `./stop-server.sh`。

---

SaysChatV4 · Java 8 · TCP · AES-128-CBC + HMAC-SHA256 · Android 5.0+
