# DeepSeek 余额 — 安卓应用

一个装在手机上的余额查看器。打开就看到 DeepSeek 账户还剩多少钱，点一下刷新。

**不依赖 Gradle，不依赖 Android Studio**，用 `tools/` 里那套工具链直接编译出 APK。

```
out/dsbalance-1.0.apk     34 KB
```

> ⚠️ **个人自用，请勿分发。** 图标用的是 DeepSeek 的商标（从官方 App 提取），仅可用于自己手机上。
> 一旦发布到应用商店或发给别人，就是另一回事了。

## 它做什么 / 不做什么

| 能做 | 不能做 |
|---|---|
| 显示总余额、充值余额、赠送余额 | ❌ 显示 token 用量 |
| 显示账户是否可用 | ❌ 显示消费金额、请求次数 |
| 下拉刷新、缓存上次数据 | ❌ 任何图表 |
| 深浅色主题切换 | ❌ 修改账户任何设置 |

**为什么没有用量统计**：官方公开 API 只提供 `/user/balance` 一个接口，用它只能拿到余额。
那些图表数据在网页仪表盘的**私有接口**后面，需要一个叫 `userToken` 的浏览器登录令牌——
**API key 对它无效**（会返回 `40003 invalid token`）。那条路脆弱且令牌比 key 更敏感，本项目刻意不走。

## 构建

```bash
python build.py
```

依次执行：`aapt2 compile` → `aapt2 link` → `javac` → `d8` → 打包 → `zipalign` → `apksigner`。

只需要 JDK（已装 JDK 25）。工具链已解压在 `tools/`，**构建过程完全离线**。

## 安装

```bash
# 1. 推到手机（不能用 adb install，见下）
MSYS_NO_PATHCONV=1 ./tools/platform-tools/adb.exe push out/dsbalance-1.0.apk /sdcard/Download/

# 2. 然后在手机上：文件管理 → 下载 → 点这个文件 → 安装
```

**为什么不能用 `adb install`**：小米澎湃系统在未开启「USB 调试(安全设置)」时会拒绝所有
数据线安装，报 `INSTALL_FAILED_USER_RESTRICTED`。而那个开关要求登录小米账号 + 插 SIM 卡。
推文件让手机上手动装，绕开了这层限制。

> Git Bash 下必须加 `MSYS_NO_PATHCONV=1`，否则 adb 会把 `/sdcard/...` 误认成
> Windows 路径 `/Program Files/Git/sdcard/...`。

## 看手机屏幕

```bash
MSYS_NO_PATHCONV=1 ./tools/platform-tools/adb.exe exec-out screencap -p > shot.png
```

## 目录结构

```
app/
  AndroidManifest.xml                        清单（包名 com.example.dsbalance）
  src/com/example/dsbalance/
      MainActivity.java                      界面 + 交互 + 边到边 + 状态栏配色
      BalanceApi.java                        发 HTTPS 请求 + 解析 JSON，6 种错误归类
      ApiKeyStore.java                       API key 的本机存储
      BalanceCache.java                      最后一次成功的结果
      ThemePrefs.java                        主题模式 + 包装 Context 实现深浅色
  res/
      values/{strings,colors,styles}.xml     浅色配色
      values-night/{colors,styles}.xml       深色配色
      layout/activity_main.xml
      drawable/                              卡片背景、按钮、状态圆点、主题图标、鲸鱼
      mipmap-anydpi-v26/ic_launcher.xml      自适应图标
build.py                                     构建脚本（无 Gradle）
extract_icon.py                              从官方 APK 提取鲸鱼矢量图（必要时自动拉取）
tools/                                       aapt2 / d8 / zipalign / apksigner / adb
out/                                         最终 APK
```

> `debug.keystore`（2.7 KB）**不要删**。它是签名密钥；重新生成会得到不同的签名，
> 新版就盖不住手机上已装的版本，必须先卸载——你填的 key 和主题设置会一起丢。

## 应用图标

图标是从手机上安装的 DeepSeek 官方 App（`com.deepseek.chat`）里**原样提取**的，
不是重画的：

```bash
python extract_icon.py      # 需要 tmp_icon/base.apk 存在
```

它做三件事：
1. 从官方 `res/PF.xml` 取出 3050 字符的鲸鱼矢量路径（逐字节一致）
2. 从官方 `res/9s.xml` 取出蓝色线性渐变 `#5d78fe → #3e5ffe`
3. 用 `<aapt:attr>` 把渐变内联进路径，生成我们自己的前景图

官方图标 = 纯白背景 + 蓝色渐变鲸鱼。编译后我的 APK 里会出现
`res/drawable/$ic_launcher_foreground__0.xml`，**和官方 APK 的结构一致**。

脚本不需要项目里存任何中间文件：`tmp_icon/base.apk` 不存在时会自动用 adb 从手机拉取
官方 APK（约 21 MB），用完可以直接删，下次运行会重新拉。已用哈希比对确认
"自动拉取"和"已有文件"两条路径生成的矢量图**逐字节相同**。

## 数据与安全

- **API key 存在本机** `SharedPreferences`（文件 `dsbalance`，键 `api_key`），**明文**
- 这是刻意的取舍：加密要引入 Android Keystore，对"只装自己手机上"的场景收益不匹配
- **key 绝不写进代码或 APK**，需要你在应用里手动填入
- 应用只访问 `https://api.deepseek.com/user/balance`，**从不访问 `platform.deepseek.com`**，
  也不碰浏览器登录态

## 技术参数

| 项 | 值 |
|---|---|
| 包名 | com.example.dsbalance |
| minSdkVersion | 26（Android 8.0） |
| targetSdk / compileSdk | 35（Android 15） |
| 第三方依赖 | 无（只用系统自带的 HttpURLConnection 和 org.json） |
| 签名 | v1 + v2，调试证书（口令 `android`） |
| 测试机型 | 小米 14（23127PN0CC），Android 16 / API 36 |

## 已验证 / 未验证

**已在真机上验证：**
- 余额真实拉取成功，字段名（`is_available`、`balance_infos`、`total_balance`…）全部正确
- 深色/浅色主题切换生效，且**独立于系统设置**（系统仍是浅色时应用可为深色）
- 主题偏好在强制关闭并重启后保留
- 重启后自动刷新，确实拉到新数据（余额数值随时间变化）
- 边到边适配正确（用 `uiautomator dump` 逐个核对了控件坐标，未被状态栏/导航条遮挡）
- 错误提示经实机测试正常

**尚未验证：**
- 6 条错误分支**没有逐一覆盖**（已确认可用，但超时 / 服务器错误 / 数据格式异常
  这三种难以主动触发）
- 缓存的"秒显"效果（网络太快，3 秒内就刷完了，看不出先显示旧数据的瞬间）

## 已知的坑

- **HyperOS 会缓存桌面图标**：更新后桌面可能仍显示旧图标，锁屏解锁或重启后才会刷新
- **`adb shell input` 被禁止**：同样的安全设置拦截，所以无法用命令行模拟点击，
  需要人工在手机上操作
- 手机系统若在 22:00–07:00 自动切深色，应用选「跟随系统」时会跟着变

## 后续可做

- 覆盖剩余的错误分支（超时、服务器错误、数据格式异常）
- 应用内嵌浏览器登录以获取 `userToken`，从而显示用量图表（代价大、脆弱，见开头）
- 余额低于阈值时发通知提醒
