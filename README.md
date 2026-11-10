# FlipMirror

把**内屏已损坏或拆除**的三星 Galaxy Z Flip5，变成一台可以用外屏正常操作的手机。

> 通过 `MediaProjection` 把内屏（display 0）的画面**不编码直出**到外屏（display 1），
> 再把外屏的触摸反向注入回内屏。

这不是通用的「外屏增强」工具，而是**针对一类硬件故障的救急方案**。使用前请先读完 [适用条件](#适用条件)。

---

## 特性

- **画面直出**：`VirtualDisplay` 输出到 SurfaceView，全程 GPU 直通，不经 MediaCodec，无花屏、延迟 < 50ms
- **触摸跟手**：Shizuku UserService + `injectInputEvent`，零进程 fork，DOWN/MOVE/UP 实时注入
- **抗前台抢占**：画面挂在 `TYPE_APPLICATION_OVERLAY` 上，不参与 Activity 栈竞争 ——
  外屏桌面 `SubHomeActivity`（`type=home` 的 Task）抢不走
- **关屏自愈**：`surfaceDestroyed` 只摘输出不销毁 VirtualDisplay，亮屏 `setSurface` 换绑，
  无需重新授权；亮屏时自动恢复 `device_state=4`
- **一键自检**：App 内按钮触发，借 Termux 执行 `device_state 4` + 拉起 Shizuku，**全程不需要电脑**
- **外屏控件条**：悬浮 `‹ ○ ▢ ✕`，任意 App 上方可用

---

## 适用条件

### ✅ 适用

- **Samsung Galaxy Z Flip5**（SM-F731N / F731B / F731U…）及 Z Flip6
- 内屏面板**损坏、黑屏或已拆除**，但**系统仍在正常运行**
- Android 14 / 15 / 16（在 One UI 8.0 + Android 16 上实测）
- 能通过 `adb` 连上，或设备已有可用 root
- 已安装 [Shizuku](https://shizuku.rikumo.com/)（推荐）或 KernelSU/Magisk

### ❌ 不适用

- **非三星机型**：核心依赖三星私有命令 `cmd device_state`
- **非折叠机**：没有 display 1
- **内屏完全正常**：没必要，这是给坏屏用的
- **未验证**：One UI 9+ / 未来大版本（`wm size` 行为、SubHome 组件名可能变动）

---

## 架构

```
┌────────────────── 外屏 display 1 (748×720) ──────────────────┐
│  BarOverlayService ── TYPE_APPLICATION_OVERLAY ──▶ ‹ ○ ▢ ✕   │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ MirrorActivity（画面也是 overlay，不占 Activity 栈）    │  │
│  │   ├─ 顶部 66px 黑带（状态栏位置）                       │  │
│  │   └─ SurfaceView 748×654 + 48px 圆角遮罩               │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
        ▲ 显示                              │ 触摸
        │                                   ▼
┌───────┴──────────┐            ┌────────────────────────┐
│ MirrorEngineImpl │            │ TouchInjectorImpl      │
│ MediaProjection  │            │ 坐标映射 + UserService │
│ AUTO_MIRROR d0   │            │ 兜底 input motionevent │
└───────▲──────────┘            └───────────┬────────────┘
        │ 采集                                │ 注入
┌───────┴────────────────────────────────────▼──────────────┐
│              内屏 display 0 (1600×1398 @480)                │
│        wm size / wm density 由 Shell 预置；device_state=4   │
└───────────────────────────────────────────────────────────┘

        Shell（统一通道：root → Shizuku → Termux-RUN_COMMAND）
```

详细说明见 [`ARCHITECTURE.html`](ARCHITECTURE.html)。

---

## 编译

```bash
# 依赖：JDK 17、Android SDK（platform 34+）、Gradle 8.x
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

cd app
./gradlew assembleDebug
```

产物：`app/app/build/outputs/apk/debug/app-debug.apk`

---

## 部署

### 1. 拉起 Shizuku（每次开机一次）

本机实测 `ro.boot.flash.locked=1`，免 root 的 Shizuku 只能由 adb 拉起，没有自启通道：

```bash
adb shell /data/local/tmp/shizuku_starter
```

### 2. 安装

```bash
adb install -r app-debug.apk

# 授权（SYSTEM_ALERT_WINDOW 必须，否则控件条和画面无法挂 overlay）
adb shell appops set com.flip.mirror SYSTEM_ALERT_WINDOW allow
adb shell appops set com.flip.mirror PROJECT_MEDIA allow
```

### 3. 打开双屏并发

```bash
adb shell cmd device_state state 4
```

> `4 = CONCURRENT_INNER_DEFAULT`（内外屏同时点亮）。这是镜像的前提：
> 其它状态下内屏是熄灭的，MediaProjection 抓不到画面。

### 4. 启动

```bash
adb shell am start --display 1 -n com.flip.mirror/.MainActivity
# 点「开始镜像到外屏」→ 授权框点「共享屏幕」
```

---

## 免电脑启动（可选，推荐）

目标：**开机后不接电脑，只点两下手机就能用**。

**原理**：App 的 `targetSdk=34`，Android 10+ 对其私有目录施加 `noexec`，
无法自己执行 adb 二进制；但 **Termux 的 `targetSdk=28` 不受此限**。
于是让 App 发 Intent，由 Termux 代跑 adb 自连命令。

### 一次性配置（需要电脑一次）

```bash
# 1) 固定 adbd 端口，让设备重启后仍监听 5555
adb shell su -c 'setprop persist.adb.tcp.port 5555'

# 2) 安装 Termux 与 Termux:Boot，进入 Termux 装 android-tools
#    （Termux 内执行：pkg install android-tools）
#    若报 cannot locate symbol __hash_memory，再执行 apt install -y libc++

# 3) 把 Termux 的 adb 公钥写进系统授权表（需要 root）
KEY=$(adb shell run-as com.termux cat files/home/.android/adbkey.pub)
adb shell su -c "echo '$KEY' >> /data/misc/adb/adb_keys"

# 4) 允许外部调用
adb shell "run-as com.termux sh -c 'printf \"\\nallow-external-apps=true\\n\" >> files/home/.termux/termux.properties'"

# 5) 把本仓库的 script/flip-prep.sh 放进 Termux
adb push script/flip-prep.sh /sdcard/
adb shell "run-as com.termux sh -c 'cp /sdcard/flip-prep.sh files/home/; chmod 700 files/home/flip-prep.sh'"

# 6) 给 App 授予 Termux 的 RUN_COMMAND 权限
adb shell pm grant com.flip.mirror com.termux.permission.RUN_COMMAND
```

> **两个「必须」**：`allow-external-apps=true` 和 Manifest 里的
> `<uses-permission android:name="com.termux.permission.RUN_COMMAND"/>`。
> 缺任何一个，`pm grant` 都会静默失败、Intent 报 `Requires permission`。

### 日常使用

```
1. 外屏点 FlipMirror
2. 点「① 启动前自检（双屏 + Shizuku）」    ← 约 8 秒
3. 点「开始镜像到外屏」→ 授权框点「共享屏幕」
```

**全程手机操作，不需要电脑。**

---

## 排障

```bash
# 镜像抓的是哪块屏（必须是 Display 0 = 内屏）
adb shell "dumpsys SurfaceFlinger | grep -A2 MirrorRoot"

# 折叠状态（必须是 CONCURRENT_INNER_DEFAULT）
adb shell "dumpsys device_state | grep mCommittedState"

# 两屏状态（都应该是 state ON）
adb shell "dumpsys display | grep -oE 'mDisplayId=[0-9]+|state [A-Z]+'"

# 触摸通道（有 :touch 进程 = UserService 快通道在跑）
adb shell "ps -A | grep 'flip.mirror:touch'"

# 应用日志（必须 -s TAG 过滤，否则被系统日志淹没）
adb logcat -s FlipMirror:V FlipMirrorBar:V FlipMirrorShell:V MirrorEngine:V
```

| 现象 | 原因 |
|---|---|
| 点图标后什么都没出现 | 开机默认 `TENT`，App 落到 display 0（看不见）。点「自检」或先 `cmd device_state state 4` |
| 点了没反应，外屏还是桌面 | 落屏失败，查 `MainActivity running on display` |
| 画面有但触摸很飘 | Shizuku 掉了，退回 `input` 慢通道 |
| 关屏后再亮屏黑屏 | `device_state` 被系统打回 `OPENED`，需重新置 4 |
| 画面全黑但引擎日志正常 | overlay 的 window format 用了 `TRANSLUCENT`，必须 `OPAQUE` |
| 自检按钮无反应 | Termux 的 `allow-external-apps` 未开，或 `RUN_COMMAND` 权限没授予 |

---

## 换机要改的常量

| 位置 | 常量 | 默认值 | 说明 |
|---|---|---|---|
| `MirrorActivity` | `CONTENT_TOP_PX` | 66 | 顶部黑带高度（外屏状态栏区） |
| `MirrorActivity` | `CORNER_R_PX` | 48 | 圆角半径（外屏物理圆角 49px @748 宽） |
| `SettingsStore` | `innerWidth/Height` | 1600 / 1398 | 内屏压缩尺寸，**比例必须 ≈ 1.144** |
| `SettingsStore` | `innerDpi` | 480 | 内屏密度，调小 = 一屏显示更多 |
| `BarOverlayService` | 黑带高度 | `66 × (appWidth/748)` | 跟随外屏宽度比例 |

⚠️ **提醒**：若设备装了「阿田」（`com.tianweixing.atianwidget`）之类的工具，
它可能给 App 配置外屏缩放 `scale:2.0`，使 `getResources().widthPixels` 返回 **1496**（=748×2）。
所有按 `getResources()` 算的尺寸都会翻倍 —— 挂 overlay 时请改用
`createDisplayContext(display).getResources()` 重算。

---

## 已知限制

1. **开机必须有人拉一次 Shizuku**（本机无自启通道）。免电脑方案见上文
2. **MediaProjection 首次要手点授权**（系统强制，无法绕过）
3. **音频不投、震动不投**：音频需额外 `AudioRecord` 通道且会双重播放；震动是硬件马达
4. **通知栏受外屏宽高比 1.144 限制**，下拉菜单无法完整展示（可降 DPI 缓解）
5. **`device_state` 无法用 App API 替代**：`CONTROL_DEVICE_STATE` 是 signature 权限，
   `requestState()` 会被 `cancel_when_requester_not_on_top` 立即撤销

---

## 许可证

MIT License，见 [LICENSE](LICENSE)。

**免责声明**：本软件会修改系统显示状态（`wm size` / `wm density` / `cmd device_state`），
**有变砖风险**。仅供在**自有设备**上修复使用。作者不对任何损坏负责。

---

## 第三方

- [Shizuku](https://github.com/RikkaApps/Shizuku) —— shell 权限通道
- [Termux](https://github.com/termux/termux-app) + `android-tools` —— 免电脑自举（可选）
- ADB / scrcpy 二进制**不包含**在本仓库内，请自行准备
