# FlipMirror 源码包

**用途**：把拆掉内屏的 Galaxy Z Flip5（SM-F731U）变成"外屏当主力机"的覆屏镜像壳。
把内屏画面实时镜像到外屏（748×720），手指点外屏等于点内屏。

**路线**：`MediaProjection` 抓内屏 → `SurfaceView` 直出到外屏（**不编码，零花屏**），
触摸反向注入回内屏（通过 Shizuku 执行 `input -d 0 tap`）。

---

## 一、编译前置

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **17** | 必须 17，低版本编译不过（用了 lambda + API 34） |
| Android SDK | **Platform 34** | compileSdk/targetSdk 都是 34 |
| Build Tools | **34.0.0** | |
| Gradle | **8.13** | AGP 8.5.2 |

**注意**：本机开发环境在 `E:/APPS/android-dev/`（JDK/SDK/Gradle 都在里面），
换到别的机器要按实际情况改路径。

---

## 二、编译步骤

### 1. 改 `local.properties`

包里带的是本机路径，**必须改成你自己机器的 SDK 路径**：

```properties
# 反斜杠会被 Properties 吃掉报"文件名/目录名语法不正确"，必须用正斜杠或双反斜杠
sdk.dir=C:/Users/你的用户名/AppData/Local/Android/Sdk
# 或者
sdk.dir=C:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

### 2. 编译

```bash
# 设环境变量
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# 编译（首次会下载 AGP 和 Shizuku 依赖，国内建议挂代理或用阿里云镜像，已在 build.gradle 配好）
./gradlew assembleDebug
# 或者用独立 gradle
gradle assembleDebug --no-daemon
```

**产物**：`app/build/outputs/apk/debug/app-debug.apk`

### 3. 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 三、运行前必须做的事

### 1. 切双屏并发模式（关键！不切则内屏是关的，镜像出来全黑）

```bash
adb shell "su -c 'cmd device_state state 4'"
```

`device_state` 编号：
```
0 = CLOSED                    合盖，两屏都关
1 = TENT                      帐篷模式 → 只外屏亮（默认）
3 = OPENED                    展开模式 → 只内屏亮
4 = CONCURRENT_INNER_DEFAULT  ★ 双屏并发 → 内外屏同时亮
```

### 2. 设置内屏分辨率与 DPI

```bash
adb shell "su -c 'wm size 748x626; wm density 340'"
```

- `748x626`：626 = 外屏 720 减去顶部状态栏 94px
- `density 340`：**必须等于外屏 DPI**，不一致会导致内容缩放、四周黑边

### 3. 授权 Shizuku

App 首次启动会申请 Shizuku 权限（弹窗选「始终允许」）。
**触摸注入完全依赖 Shizuku** —— 因为 KernelSU 的 `su` 对 App 进程做了挂载隔离，
App 里 `Runtime.exec("su")` 会报 `No such file or directory`。

### 4. 开启无障碍服务（防全面屏手势退出）

```
设置 → 无障碍 → 已下载的服务 → FlipMirror
```

不开的话，从屏幕边缘滑动会被系统当成返回/回桌面，直接把壳退出去。

---

## 四、启动与使用

```bash
adb shell "am start -n com.flip.mirror/.MainActivity"
```

流程：
1. 点「开始镜像到外屏」
2. 授权框出现 → 点 Spinner 选「**共享整个屏幕**」
3. 点「下一步」→「共享屏幕」
   - ⚠️ 授权框在 626 高度下按钮可能被挤出屏幕，需要小幅上滑才露出来
4. 开始镜像

**退出**：长按顶部控件条

---

## 五、源码结构

```
app/src/main/java/com/flip/mirror/
├── MainActivity.java          起点页，发起 MediaProjection 授权
├── MirrorActivity.java        镜像页：SurfaceView + ControlBar 分层承载
│                              （也包含启动自检 ensureDeviceState、LockTask 尝试、退出）
├── MirrorEngine.java          引擎接口
├── MirrorEngineImpl.java      MediaProjection → createVirtualDisplay → Surface
├── MirrorService.java         前台服务（mediaProjection 类型，Android 14 强制要求）
├── TouchInjector.java         注入接口
├── TouchInjectorImpl.java     Shizuku 注入（反射 newProcess 执行 input 命令）
├── ControlBar.java            顶部控件条（返回/主页/多任务/收起，32dp 圆钮）
├── GestureBlockService.java   无障碍服务，消费系统手势（待启用）
└── BootReceiver.java          开机自启（**已从 Manifest 移除**，保留代码供参考）

app/src/main/res/
├── values/strings.xml
└── xml/accessibility_service_config.xml
```

---

## 六、关键实现细节（改之前必读）

### 布局分层

```java
FrameLayout (748x720)
├── ControlBar     y = 0~94      高 94px 贴顶（占状态栏那条）
└── SurfaceView    y = 94~720    高 626，topMargin = 94
```

不是叠在一起，是**上下分层**，谁也不压谁。

窗口要覆盖到状态栏区域，必须加：
```java
getWindow().setFlags(FLAG_LAYOUT_NO_LIMITS, FLAG_LAYOUT_NO_LIMITS);
winLp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
getWindow().setDecorFitsSystemWindows(false);   // Android 11+
```

### Android 14 的 MediaProjection 三道坎

1. `getMediaProjection()` 必须在 **mediaProjection 类型的前台服务已运行之后**调用
2. `startForeground(id, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` 三参数版
3. `resultData` **只能消费一次**，一个令牌只能建一次 VirtualDisplay

### SurfaceView 格式

必须 `PixelFormat.OPAQUE`，用 `TRANSLUCENT` 会黑屏。

### VirtualDisplay flags

只用 `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`，**不要加 `PUBLIC`**（会导致画面不落到 SurfaceView）。

### 触摸注入写法

```java
// Shizuku.newProcess 是 private，必须反射
Method m = Shizuku.class.getDeclaredMethod(
        "newProcess", String[].class, String[].class, String.class);
m.setAccessible(true);
m.invoke(null, new String[]{"/system/bin/sh", "-c", "input -d 0 tap 100 200"}, null, null);
```

**坐标映射**：`外屏坐标 × (内屏宽 / 外屏宽)`，
`setGeometry` 的目标尺寸必须传**内屏实际值 748x626**（写死 1080x1920 会导致 y 越界失效）。

---

## 七、已知限制

| 限制 | 原因 | 状态 |
|---|---|---|
| 全面屏手势会退出 | `setSystemGestureExclusionRects` 有 200dp 上限 | 无障碍服务已写，需手动开启 |
| Lock Task 用不了 | 跨屏（display 1）调用报 `Invalid task, not in foreground`；Device Owner 因设备有账号设不了 | 放弃 |
| 每次启动要手点授权 | MediaProjection 强制要求用户同意 | 无法绕过 |
| 内屏会自己掉回 TENT | 折叠检测 | App 启动时自检重切 `state 4` |

---

## 八、其他文档

- **`DESIGN-settings.md`** —— 设置界面的完整设计（5 张卡片、全部可调项、实现顺序）
- 桌面上还有一份 **`FlipMirror-交接文档-20261110.md`**，含完整的技术探索过程、
  17 条踩坑清单、环境路径、快速上手步骤

---

## 九、调试

```bash
# 应用日志
adb logcat -d | grep -a "FlipMirror"
adb logcat -d | grep -a "TouchInjector"

# 正常启动应看到这些
# TouchInjector init ok=true
# getMediaProjection ok = true
# engine start = true (748x626)
# ensureDeviceState done

# 查双屏状态
adb shell "dumpsys device_state | grep mCommittedState"

# 查镜像目标（应该是 Display 0）
adb shell "dumpsys SurfaceFlinger | grep -A2 MirrorRoot"

# 抓屏（要用 64 位 display id，不是 0/1）
adb shell "dumpsys SurfaceFlinger --display-id"
# Display 4630947232161729155 (HWC display 0)   ← 内屏
# Display 4630947181303254916 (HWC display 3)   ← 外屏
```

---

*打包时间：2026-11-10*
