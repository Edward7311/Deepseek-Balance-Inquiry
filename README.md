# DeepSeek 余额 — 安卓应用

装在手机上的 DeepSeek 账户工具。不用 Gradle、不用 Android Studio。

## 三块功能

| 区块 | 说明 |
|---|---|
| **余额** | 总余额、充值余额、账户是否可用。打开秒显，**永不失效** |
| **用量** | 近 30 天的汇总、按天消费柱状图、按模型列表。**依赖私有接口，可能失效** |
| **官网界面** | 直接嵌入 `platform.deepseek.com`，点右上角地球图标打开 |

三块**互相独立**：用量那块整个坏掉，余额照常显示。

## 构建

```bash
python build.py
```

只用 JDK。工具链在 `tools/`，构建全程离线。

## 安装

**不能用 `adb install`**——手机系统会拦截，报 `INSTALL_FAILED_USER_RESTRICTED`。
推到手机再手动装：

```bash
MSYS_NO_PATHCONV=1 ./tools/platform-tools/adb.exe push out/dsbalance-1.0.apk /sdcard/Download/
```

然后在手机上：**文件管理 → 下载 → 点这个文件 → 安装**。
覆盖安装即可，已填的 key 和主题设置都会保留。

## 源码分工

| 类 | 干什么 |
|---|---|
| `MainActivity` | 宿主：主题、边到边、装配两个区块 |
| `BalanceSection` | 余额区（官方接口，独立可靠） |
| `UsageSection` | 用量区的界面与流程 |
| `UsageSession` | WebView 生命周期、登录检测、发请求 |
| `UsageBridge` | JS 桥，唯一的 JS↔Java 通道 |
| `UsageModel` | 私有接口的解析 + 格式化 |
| `BarChartView` | 自绘柱状图（无图表库） |

其余：`BalanceApi` / `BalanceCache` / `ApiKeyStore` / `ThemePrefs`。

## 两条要注意的

- **`debug.keystore` 不要删。** 它是签名密钥；重新生成会得到不同的签名，
  新版就盖不住手机上已装的版本，必须先卸载——key 和主题设置会一起丢。
- **`tools/` 不用管。** 那是 Google 的 Android SDK（279 MB），已被 `.gitignore`
  排除，随时可以重新下载。

## 更多

- **[docs/notes.md](docs/notes.md)** —— 踩过的坑：构建、私有接口、内嵌浏览器
  三组共 15 条。**卡住的时候先翻这个。**
- **[docs/api-sample-2026-10-03.txt](docs/api-sample-2026-10-03.txt)** ——
  私有接口的真实原始返回。接口哪天变了，对着它比对就知道哪里变了。
