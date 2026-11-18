# 交接：FlipMirror「secure 页面看得到」这条线（2026-11-18）

> 读这一份就够。同一天踩的坑都在这，回家照着验能复现。

## 一、一句话现状

**机制已验证可行**（secure 页面能原生显示在外屏、内容完整、触摸正常），
**但「自动搬过去」还不稳**（我这边三连测 3/3 通过，V 实测失败过一次 `-> false`）。
失败时行为是安全的：会回滚成镜像，不会留在黑屏。

## 二、版本与提交（都已推 GitHub，本地=远端）

| 版本 | 提交 | 内容 |
|---|---|---|
| 2.4.1 | `cfddf09` | 自检第 9 项「受保护内容」（`dumpsys SurfaceFlinger` 的 `(Secure)` 层） |
| 2.4.2 | `bcf8efc` | 入口页「搬到外屏」按钮（第一版，用 stopService，**有 bug**） |
| 2.4.3 | `de8bc87` | 自检第 10 项「原生外屏态」（信息项，不计异常） |
| 2.4.4 | `9694fa2` | 退出改 `hideNow()`→`shutdown()`（修触摸错位） |
| 2.4.7 | `6053c82` | D 方案自动感应（提示条 + 三信号判定），wip |
| 2.4.9 | `d5f49da` | **搬之前停 MediaProjection** ← 决定性一步 |
| 2.5.3 | `0d66d6a` | 稳定化：`shutdown(false)`、按包名校验、双次确认、清 token、搬运前 force-stop |

`dist/` 里有 `2.4.1/2.4.2/2.4.3/2.4.4/2.4.9/2.5.3` 的 APK（中间 2.4.5~2.4.8 未单独出包）。
手机当前装的是 **2.5.3**。

## 三、能用的 / 不能用的

**能用**
- 镜像本身（照旧）
- 自检第 9 项：能判出"当前页有受保护内容，镜像必然黑"
- 自检第 10 项：能判出"当前处于原生态（镜像已停）"
- **机制**：停投影 + 页面原生落 display 1 ⇒ secure 页内容可见（实测亮度 **240.6**）、触摸正常

**不稳 / 不能用**
- 自动感应后的「搬过去」：偶发 `move to cover -> false`（V 18:20 那次就是）
- 「搬回内屏」：走 `no projection data, re-acquire via MainActivity`，中途被打断会停黑屏；
  点入口页「开始镜像」能恢复（`projection started`）

## 四、决定性的技术结论（别丢）

1. **secure 页面在镜像里必然全黑** —— MediaProjection 的虚拟屏不是 secure 显示。
   `screencap` 同页亮度 **0.9**；判据要看合成器 `dumpsys SurfaceFlinger | grep -c '(Secure)'`，
   **不要看窗口 `fl=`**（那页 `fl=81810100` 里没有 `0x2000`，会误判成没被保护）。
2. **display 1 本身就是 secure 显示**（`FLAG_SECURE` + `staticInfo.secure=true`）⇒
   页面**原生**跑在外屏完全正常。这是绕过防截屏的唯一零权限路径。
3. **只要 MediaProjection 会话活着，系统就判「屏幕共享中」** ⇒ secure 应用自我遮挡并弹
   「为安全起见，屏幕共享画面已隐藏此应用的内容」——**即使页面已经原生在 display 1 上**。
   实测对照：投影活 16.6 / 终止投影 **217.9**。⇒ 搬之前必须 `mProjection.stop()`。
4. 要真正抓 secure 像素只能建 `VIRTUAL_DISPLAY_FLAG_SECURE` 的虚拟屏，它要
   `CAPTURE_SECURE_VIDEO_OUTPUT`（签名级）；shell(2000) 实测被拒。探针见 `rootcap/`。

## 五、今天踩的五个坑（都有实测证据）

| # | 坑 | 现象 | 解法 |
|---|---|---|---|
| 1 | `stopService` 停不掉本服务 | 服务 `START_STICKY`，停完仍 1 个；遮罩继续把触摸注入 display 0 | 用 `shutdown()`（alpha=0 + `FLAG_NOT_TOUCHABLE` + 释放唤醒锁 + `desired=false`） |
| 2 | `shutdown()` 自带的「送阿田首页」线程（`state 1 + PowerAssistActivity`）与搬运的 `state 4` 抢 | 停在首页 | 拆 `shutdown(sendHome)`，搬运走 `shutdown(false)`；**SubHome 只在 state 1 存在，state 4 下外屏是空地** |
| 3 | 校验按组件全名 | Settings 子页被重定向（`$DevelopmentSettingsActivity → .Settings`）⇒ 判失败 ⇒ 误回滚把成功的搬运盖回去 | 按**包名**判定 |
| 4 | 单次命中就判成功 | 页面刚落 display 1，系统又把 SubHome 拉回来 | **两次命中间隔 1.2s** 才算稳 |
| 5 | 停投影后复用旧 token | `RemoteException: MediaProjection.isValid` | 停投影时清 `sResultCode/sResultData`，重走 MainActivity 重新授权 |

附带：`am start --display 1` 对"已在 display 0 前台"的页面只会提到前面、落不到外屏
⇒ 搬运前 `am force-stop <包名>`（`state 4` 之后外屏要约 **2 秒**才 `app_accessible`，0.4s 太短）。

## 六、代码落点（要改就从这些地方下手）

文件：`app/app/src/main/java/com/flip/mirror/CoverOverlayMirrorService.java`

| 方法 / 常量 | 作用 |
|---|---|
| `startSecureWatch()/tickSecureWatch()` | 1s 节拍：全黑 ≥6s 才往下走 |
| `checkProtectedContent()` | 三信号复核：`device_state` 含 `CONCURRENT_INNER_DEFAULT` + `(Secure)` 层 >0 + 前台不是本 App |
| `showPromptBar()` | 提示条，**放屏幕底部**（原来放顶部会落在控件条"唤醒区"里，第一下点击被 `mSwallowGesture` 吞掉） |
| `startMoveToCover()` | 搬运主体：`shutdown(false)` → `stopProjectionInternal()` → 轮询就绪 → `force-stop` → `am start --display 1` → 双次校验 |
| `waitCoverReady()/waitComponentOnCover()` | 轮询助手（后者要求两次命中） |
| `stopProjectionInternal()` | `mProjection.stop()` + `releaseProjection()` + 清 token |
| `shutdown(boolean sendHome)` | `sendHome=false` 时跳过"送首页"线程 |

`MainActivity.moveToCover()` 是入口页按钮那条路（现在基本被自动感应取代，仍保留）。

## 七、回家怎么验（命令）

```bash
A="H:/AI PG/AD APP/FlipMirror/tools/pt34/platform-tools/adb.exe"   # 或你自己机器上的 adb
$A shell "am force-stop com.flip.mirror"
$A shell "cmd device_state state 4"
$A shell "am start --display 1 -n com.flip.mirror/.MainActivity"   # 入口页 → 点「开始镜像」
$A shell "am start --display 0 -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS"   # 打开 secure 页
# 等 8~12 秒，外屏底部应出现「受保护内容 · 搬到外屏看」
$A shell "input -d 1 tap 578 672"          # 点「搬过去」
$A shell "dumpsys activity activities | grep topResumedActivity | tail -1"   # 应含 com.android.settings/
$A shell "screencap -d 4630947181303254916 -p /sdcard/x.png" && $A pull /sdcard/x.png x.png
# 判据：x.png 亮度均值 >200（内容可见）；<50 = 失败
```

**失败判据**：日志出现 `move to cover: ... -> false`，随后 `mirror shown again`（回滚成功，安全）。

## 八、设备侧状态（2026-11-18 18:30）

- 手机 SM-F731N / Android 16 / One UI 8，安全补丁 2026-04-05，开机 118h 无重启
- 已装 FlipMirror **2.5.3**
- `stay_on_while_plugged_in=15`（我设的防休眠，可改回）
- 临时 root（RootMyF7310）：**今天两次引导都失败**（运行记录：失败·未完成（设备重启或 App 中断）），
  过程里 adb 掉线 24~60s、app 重启；详情页「内核」字段空白。
  ⚠️ **不是"只服务它自己"** —— 11-09 从 adb shell 跑通过 `su -c id → u:r:ksu:s0`，今天是引导没成功。

## 九、我建议的下一步（按性价比）

1. **先查 `-> false` 的那一次**：日志里 `waitCoverReady` 有没有打 `cover not ready in 6s`，
   以及两次 `am start` 前后 `dumpsys activity activities` 的实际栈 —— 这能把最后一个竞态钉死。
2. 若还稳不住：退一步，把「搬过去」做成**人工两步**（先 ✕ 退镜像 → 再点按钮），牺牲一步换确定性。
3. 不要走 root 抓帧：本机临时 root 现在连加载都失败，且即使成功也要改架构接 root 通道。
