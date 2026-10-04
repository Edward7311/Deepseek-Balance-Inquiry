# 项目笔记

README 之外的参考材料。**卡住的时候先翻这里**——下面每条都是花时间才搞清楚的，
不写下来会重踩一遍。

---

## 技术参数

| 项 | 值 |
|---|---|
| 包名 | com.example.dsbalance |
| minSdkVersion | 26（Android 8.0） |
| targetSdk / compileSdk | 35（Android 15） |
| 第三方依赖 | **无**（只用系统自带的 HttpURLConnection / org.json / WebView） |
| 签名 | v1 + v2，调试证书（口令 `android`） |
| 测试机型 | 小米 14（23127PN0CC），Android 16 / API 36 |

---

# 踩过的坑

## 一、构建与安装

### 1. APK 大小按 4096 字节取整，"大小没变"不代表没更新

构建里的 `zipalign -p 4` 会把不压缩存放的 `resources.arsc` 补齐到 4096 的整数倍。
后果：小改动（几百字节到几 KB）**完全不会体现在文件大小上**。

实测：加 100 字节的字符串 → 总大小纹丝不动；加 5000 字节 → 正好 +4096。

**判断构建有没有生效，要看内容而不是大小：**

```bash
aapt2 dump resources out/dsbalance-1.0.apk | grep id/新控件id
aapt2 dump xmltree out/dsbalance-1.0.apk --file res/layout/xxx.xml
grep 方法名 build/dex/classes.dex
```

### 2. `out/*.idsig` 是 v4 签名文件，用不到

`apksigner` 默认会生成 `<apk>.idsig`，那是给 `adb install --incremental`（增量安装）
用的，普通安装完全不需要。已在 `.gitignore` 里排除。

### 3. 小米禁止数据线安装，`adb shell input` 也被禁

和禁止 `adb install` 是同一个开关（「USB 调试(安全设置)」，要求登录小米账号 + 插 SIM 卡）。
所以**没法用命令行模拟点击**——测试时得人工在手机上操作。

能用的只有：截图、`am start`、`am force-stop`、`dumpsys`。

### 4. Git Bash 会改写设备路径

不加 `MSYS_NO_PATHCONV=1` 的话，`/sdcard/...` 会被当成 Windows 路径改写成
`/Program Files/Git/sdcard/...`，报 `secure_mkdirs() failed`。

`adb pull` 的目标路径也要用 Windows 形式写。

### 5. HyperOS 会缓存桌面图标

更新后桌面可能仍显示旧图标。锁屏解锁一次，或者重启。

---

## 二、私有接口

这些是从网页仪表盘逆向出来的，官方没有文档。

### 6. `start`/`end` 必须对齐到本地零点，否则 `INVALID_PARAM`

路径和参数名都对、只有时间戳不对，照样失败。官网发的是
`2026-09-04T00:00+08:00` 和 30 天之后；发"当前时间减 30 天"会被拒。

对齐公式：

```java
long dayStart = Math.floor((now + tz) / 86400) * 86400 - tz;
long end = dayStart + 86400;
long start = end - 30 * 86400;
```

（`tz` = `-TimeZone.getDefault().getOffset()/1000`，北京是 28800）

### 7. 必须带 `Authorization` 头，cookie 不够

只带 cookie 请求会返回：

```json
{"code":40002,"msg":"Missing Token","data":null}
```

所以依赖 localStorage 里那个 `userToken` 的逻辑**删不掉**。

### 8. `amount` 和 `cost` 的返回包装不一样

```
by_api_key/amount   →  data.biz_data.series[]
by_api_key/cost     →  data.biz_data.data[].series[]   ← 多一层，按币种分
```

两条路不能共用同一个遍历函数。

### 9. HTTP 状态码永远是 200，真正的结果在 `biz_code` 里

```json
{"code":0,"msg":"","data":{"biz_code":1,"biz_msg":"INVALID_PARAM","biz_data":null}}
```

只看 HTTP 状态会把失败当成成功。解析前必须先检查 `biz_code == 0`。

### 10. `userToken` 不是 JWT

它是 `{"value":"<64字符>"}` 这种 JSON，要先 `JSON.parse(raw).value` 再取
（这一步是对的），但**不要按 JWT 去解**。它开头是 `PCJC...` 而不是 `eyJ...`。

---

## 三、内嵌浏览器

### 11. 登录检测不能靠"等固定几秒"

网页是单页应用（SPA），登录成功后把令牌写进 localStorage 而**不重新加载页面**，
所以 `onPageFinished` 只触发一次，得靠页面里一段轮询去发现。

**等待窗口必须给足。** 官网页面上有横幅、图片、提示框，冷启动加载可能超过十秒；
早先设的 8 秒上限会导致**明明登录着却报"未登录"**。

现在：启动检查 90 秒，明确打开登录页时 300 秒，轮询间隔 0.8 秒。
另外如果页面地址看起来是登录页，会提前判定，不用等满。

### 12. 打开浏览器时要重新装一次监听

如果页面**已经加载过**（启动时那次静默检查加载的），再从地球图标打开不会触发
`onPageFinished`，监听器就不会安装——**在浏览器里登录了应用也不知道**。

所以 `showBrowser()` 里要主动 `evaluateJavascript(watchScript(...))` 一次。

### 13. 别让"打开浏览器"和"去登录"混在一起

早先 `onLoggedIn` 无条件收起浏览器，而打开浏览器时会立刻检测到已有登录
（监听器轮询 0.8 秒就发现了），结果**点开一秒后自己弹回去**。

现在只有"打开时本来未登录、登录成功了"才自动收起。

### 14. 页面用 1dp 而不是 GONE 挂在后台

Android WebView 会对**不可见**的 view 节流定时器。缩到 1dp 但保持 VISIBLE，
页面里的 JS 定时器才能正常跑。

### 15. 切换主题会重建 Activity，WebView 会重新加载

主题切换走 `recreate()`，WebView 被销毁重建、页面重新加载。所以切换主题会把
浏览器界面收回去，这是预期行为。

---

# 已验证 / 未验证

**已在真机上验证：**

- 余额真实拉取成功，`is_available` / `balance_infos` / `total_balance` 等字段名全部正确
- 用量三张汇总卡、柱状图、按模型列表的数据与官网一致
- 柱状图点选可用，**且从图表区域拖动仍能正常滚动页面**
- 深色 / 浅色主题切换生效，**独立于系统设置**（系统是浅色时应用可为深色）
- 主题偏好在强制关闭并重启后保留
- 边到边适配正确（用 `uiautomator dump` 逐个核对了控件坐标，未被状态栏 / 导航条遮挡）
- 登录会话跨应用重启保留
- 浏览器界面进出正常，地球图标与「完成」按钮都能收回
- 错误提示经实机测试正常

**尚未验证：**

- 6 条余额错误分支没有逐一覆盖（超时 / 服务器错误 / 数据格式异常难以主动触发）
- 缓存的"秒显"效果（网络太快，3 秒内就刷完了，看不出先显示旧数据的瞬间）
- 「从未登录过」时累计消费卡片的完整表现（最坏会等满 90 秒才显示「需登录」）

---

# 后续可做

- 覆盖剩余的错误分支
- 余额或用量低于阈值时发通知
- 累计消费也做成小图（现在只有数字）

---

# 设计上的一个取舍

最初是把官网仪表盘**用原生界面复刻**的。做完了才发现，"直接嵌一个浏览器打开官网"
也是一条路——而且更简单（几百行 vs 上千行），功能还更全。

**现在两套都留着，因为定位不冲突：**

| | 原生界面 | 官网界面 |
|---|---|---|
| 打开速度 | 余额秒开（读缓存） | 首次几秒 |
| 用量概览 | 一屏看完，不用滚 | 要滚很久 |
| 功能完整度 | 只有这里做的那些 | 全部（筛选、导出、管理 key、充值） |

**下次要复刻任何网页之前，先问一句：你要的是"一眼看到数字"，还是"能操作官网"？**
