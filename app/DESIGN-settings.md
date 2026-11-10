# FlipMirror 设置界面设计（明天按此实现）

> 目标：把今天所有硬编码的参数全部暴露成可调项，做成一个"设置外壳"，
> 然后一项一项接功能。风格参考 FLIP 外屏助手（深色 + 大圆角卡片 + 两行列表项）。

---

## 一、设置界面结构

```
设置页（SettingsActivity）
├─ 顶部栏：← 返回 + "FlipMirror 设置" + 右上角「重置」
├─ 分组卡片 1：镜像参数
├─ 分组卡片 2：显示与布局
├─ 分组卡片 3：触摸与输入
├─ 分组卡片 4：行为
├─ 分组卡片 5：高级 / 调试
└─ 底部：版本号 + 设备信息（内屏/外屏实际尺寸）
```

**卡片样式**（照 FLIP 外屏助手）：
- 背景 `#1C1C1E`，圆角 20dp，左右 margin 16dp，卡片间距 12dp
- 分组标题：14sp，`#8E8E93`，左对齐，上方 margin 20dp
- 列表项：高 56dp，左图标 24dp + 主标题 16sp 白 + 副标题 13sp 灰
- 分隔线：`#2C2C2E`，1px，左侧缩进 56dp

**控件类型**：
- 开关（Switch）→ 布尔
- 滑块（SeekBar）+ 数值 → 数值
- 输入框（EditText）→ 尺寸/文本
- 选择器（点击弹 Dialog）→ 枚举
- 按钮（点击执行动作）→ 命令

---

## 二、设置项清单

### 卡片 1：镜像参数

| 项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| 内屏宽度 | 输入框 | 748 | `wm size` 的宽，改了要重新应用 |
| 内屏高度 | 输入框 | 626 | 626 = 720 - 94（状态栏） |
| 内屏 DPI | 输入框 | 340 | **必须与外屏 DPI 一致**，否则缩放 |
| 应用分辨率 | 按钮 | — | 执行 `wm size WxH` + `wm density D` |
| 恢复原始分辨率 | 按钮 | — | 执行 `wm size reset` + `wm density reset` |
| 画面缩放模式 | 选择器 | 适应 | 适应（等比留黑）/ 拉伸（填满）/ 1:1（裁切） |
| 镜像帧率上限 | 滑块 | 60fps | 30 / 45 / 60，省电用 |

> 只读显示：外屏实际 748x720 @340dpi（从 DisplayMetrics 读）

### 卡片 2：显示与布局

| 项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| 控件条位置 | 选择器 | 顶部 | 顶部 / 底部 / 左侧 / 右侧 / 悬浮 |
| 控件条高度 | 滑块 | 94px | 40~120，顶部时建议 = 状态栏高度 |
| 内容区起始 Y | 滑块 | 94px | 镜像画面 TOP 偏移，自动跟随控件条高度 |
| 控件条透明度 | 滑块 | 96% | 30%~100% |
| 按钮直径 | 滑块 | 32dp | 24~56 |
| 显示哪些按钮 | 多选 | 返回/主页/多任务/退出 | 返回 / 主页 / 多任务 / 退出 / 收起 / 键盘 |
| 显示系统状态栏 | 开关 | 开 | 关 = 用 `immersive.status=*` 隐藏内屏状态栏 |
| 全屏沉浸 | 开关 | 开 | FLAG_LAYOUT_NO_LIMITS + 挖孔全屏 |
| 状态栏时钟（自绘） | 开关 | 关 | 在控件条左侧画时间和电量（替代被隐藏的系统状态栏） |

### 卡片 3：触摸与输入

| 项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| 触摸注入 | 开关 | 开 | 总开关 |
| 注入方式 | 选择器 | Shizuku | Shizuku / Root(su) / 反射（自动降级） |
| Shizuku 状态 | 只读 | — | 显示 ping / granted，点击可重新申请 |
| 坐标映射校正 | 滑块 x/y | 0 / 0 | ±20px 微调，用于对齐边缘 |
| 长按判定阈值 | 滑块 | 300ms | 超过算 swipe 而不是 tap |
| 边缘手势拦截宽度 | 滑块 | 60px | 无障碍服务用，从边缘多少像素内算"边缘滑动" |
| 物理键盘映射 | 开关 | 关 | 外接键盘的按键转发到内屏（`input -d 0 keyevent`） |

### 卡片 4：行为

| 项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| 启动自检 | 开关 | 开 | 进镜像页时自动 `device_state state 4` + 应用分辨率 |
| 自检重试次数 | 滑块 | 2 | 失败重试几次 |
| 开机自启 | 开关 | 关 | **注意：今天实测 BootReceiver 会干扰，默认关** |
| 保持屏幕常亮 | 开关 | 开 | `FLAG_KEEP_SCREEN_ON` |
| 退出方式 | 多选 | 长按控件条 | 长按控件条 / 双击画面 / 退出按钮 / 三指下滑 |
| 退出时恢复内屏 | 开关 | 开 | 退出时 `wm size reset`，不留下 748x626 |
| 自动切回单屏 | 开关 | 关 | 退出时 `device_state state 1`（回帐篷模式） |

### 卡片 5：高级 / 调试

| 项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| 显示调试浮层 | 开关 | 关 | 显示 FPS / 延迟 / 触摸坐标 / VD 尺寸 |
| 调试浮层位置 | 选择器 | 左上 | 四角可选 |
| 日志级别 | 选择器 | INFO | VERBOSE / DEBUG / INFO / WARN / 只报错 |
| 导出日志 | 按钮 | — | 把 logcat 里 FlipMirror 的日志存到 Download |
| 导出配置 | 按钮 | — | 把 SharedPreferences 存成 JSON |
| 导入配置 | 按钮 | — | 从 JSON 恢复 |
| 重置所有设置 | 按钮 | — | 二次确认后清空 |
| 测试：注入一次点击 | 按钮 | — | 在屏幕中心打一个点，验证注入通道 |
| 测试：截内屏 | 按钮 | — | `screencap -d <内屏id>` 存到 Download |

---

## 三、实现顺序（明天按这个走）

**第 1 步：只做外壳（不接功能）**
- `SettingsActivity` + 布局（纯代码构建，不用 XML，跟现有风格一致）
- `SettingsStore`（SharedPreferences 封装，所有 key 集中定义）
- 所有列表项先只显示 + 保存值，**不生效**
- 目标：能打开、能改、能记住，重启后值还在

**第 2 步：接最关键的 5 项**
1. 内屏分辨率 + DPI + 应用按钮（最容易，直接 runShell）
2. 控件条位置 + 高度 + 透明度
3. 按钮直径 + 显示哪些按钮
4. 触摸注入开关 + 注入方式
5. 启动自检开关

**第 3 步：接布局类**
- 内容区起始 Y、全屏沉浸、隐藏系统状态栏、自绘时钟

**第 4 步：接行为类**
- 退出方式、退出时恢复内屏、保持常亮

**第 5 步：接调试类**
- 调试浮层、导出日志/配置、测试按钮

---

## 四、数据存储设计

```java
// SettingsStore.java
public class SettingsStore {
    // 镜像
    public static final String K_INNER_W     = "inner_w";      // int, 748
    public static final String K_INNER_H     = "inner_h";      // int, 626
    public static final String K_INNER_DPI   = "inner_dpi";    // int, 340
    public static final String K_SCALE_MODE  = "scale_mode";   // int, 0=fit
    public static final String K_FPS_LIMIT   = "fps_limit";    // int, 60

    // 布局
    public static final String K_BAR_POS     = "bar_pos";      // int, 0=top
    public static final String K_BAR_HEIGHT  = "bar_height";   // int, 94
    public static final String K_CONTENT_Y   = "content_y";    // int, 94
    public static final String K_BAR_ALPHA   = "bar_alpha";    // int, 96
    public static final String K_BTN_DP      = "btn_dp";       // int, 32
    public static final String K_BTN_MASK    = "btn_mask";     // int, bitmask
    public static final String K_HIDE_SYSBAR = "hide_sysbar";  // bool, true
    public static final String K_IMMERSIVE   = "immersive";    // bool, true
    public static final String K_DRAW_CLOCK  = "draw_clock";   // bool, false

    // 输入
    public static final String K_TOUCH_ON    = "touch_on";     // bool, true
    public static final String K_INJECT_MODE = "inject_mode";  // int, 0=shizuku
    public static final String K_OFFSET_X    = "offset_x";     // int, 0
    public static final String K_OFFSET_Y    = "offset_y";     // int, 0
    public static final String K_LONG_MS     = "long_ms";      // int, 300
    public static final String K_EDGE_PX     = "edge_px";      // int, 60

    // 行为
    public static final String K_AUTO_CHECK  = "auto_check";   // bool, true
    public static final String K_AUTO_RETRY  = "auto_retry";   // int, 2
    public static final String K_BOOT_START  = "boot_start";   // bool, false
    public static final String K_KEEP_AWAKE  = "keep_awake";   // bool, true
    public static final String K_EXIT_MASK   = "exit_mask";    // int, bitmask
    public static final String K_RESTORE_ON_EXIT = "restore_exit"; // bool, true

    // 调试
    public static final String K_DEBUG_OVERLAY = "debug_overlay"; // bool, false
    public static final String K_LOG_LEVEL   = "log_level";    // int, 3
}
```

---

## 五、需要注意的坑（今天踩过的，实现时别再犯）

1. **DPI 必须内外屏一致** —— 内外屏不一致会缩放，改 DPI 后必须重启镜像才生效
2. **改分辨率后 SurfaceView 尺寸也要跟着变** —— 两处要联动
3. **`wm size` / `wm density` 走 Shizuku**，不用 su（App 里 su 不可见）
4. **Shizuku.newProcess 是 private**，必须反射
5. **内屏会自动掉回 TENT** —— 自检要放在 MirrorActivity 的 onCreate 里，且要有重试
6. **BootReceiver 不要随便开** —— 会拉起 MainActivity 到 display 0
7. **授权弹窗在 626 高下按钮会出屏幕** —— 设置页别做得太高，或者做成可滚动
8. **改设置后要提示"需要重启镜像生效"** —— MediaProjection 令牌不能复用

---

## 六、文件清单（明天要新建的）

```
SettingsActivity.java     设置页主体
SettingsStore.java         配置读写（SharedPreferences）
SettingItem.java           列表项数据模型（标题/副标题/类型/当前值/key）
SettingsAdapter.java       列表适配器（如果用 RecyclerView；也可以纯 LinearLayout 拼）
widget/SwitchRow.java      开关行
widget/SeekRow.java        滑块行
widget/InputRow.java       输入行
widget/ChoiceRow.java      选择行
```

> 为了跟现有风格一致（纯 Java 构建 UI、不用 androidx 布局），
> 建议**用 LinearLayout 手工拼**，不用 RecyclerView，代码更少更直接。

---

---

## 七、关于「参考 FLIP 外屏助手代码」的结论

**扒过了，没必要。** 记录一下免得明天再折腾：

- **反编译尝试**：提了 APK（2.7MB，`io.github.flipcover.controls` v0.18.98），
  jadx 跑完加载 2175 个类 / 11028 个方法，**耗时 1h34m**，
  但中途被 `taskkill java.exe` 打断，产物目录没写出来（空的）。
- **为什么不再跑一次**：它不是镜像方案 —— 它做的是「选一个 App 用 `--display 1` 直接跑在外屏」，
  **跟我们的 MediaProjection 镜像完全是两条路**，没有可搬的实现代码。
- **唯一有价值的是 UI 风格**，已经从截图提取到了：
  - 深色主题，纯黑底 + `#1C1C1E` 圆角卡片
  - 圆角约 20~24dp，卡片间距 12dp，左右 margin 16dp
  - 列表项两行：主标题 16sp 白色 + 副标题 13sp 灰色 `#8E8E93`
  - 图标是细线风格，24dp，无边框无底色
  - 开关是系统蓝 + 白色圆钮
  - 分组标题 14sp 灰色，上方 margin 20dp
- **如果明天非要看它的代码**：别全量反编译，用
  `java -jar jadx-1.5.0-all.jar --single-class <类名> flipcover.apk`
  只反编译单个类，几秒钟出结果。先 `unzip -l` 看类名；
  布局资源被混淆了（APK 里没有 `res/layout`），别指望能扒到 XML。

---

*设计时间：2026-11-09 22:56*
*状态：待实现（明天从「第 1 步：只做外壳」开始）*
