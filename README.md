# DeepSeek 余额 — 安卓应用

一个装在手机上的余额查看器。打开就看到 DeepSeek 账户还剩多少钱，点一下刷新。

**不依赖 Gradle，不依赖 Android Studio**，用 `tools/` 里那套工具链直接编译出 APK。

```
out/dsbalance-1.0.apk     34 KB
```

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
