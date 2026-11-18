# rootcap —— secure 虚拟屏可行性探针

用途：验证「能不能拿到受保护页面（开发者选项 / 密码页）的像素」。
结论已经写进馆内 **PT-642**，这里保留可复跑的探针。

## 结论（2026-11-18 实测，SM-F731N / Android 16 / One UI 8）

| 身份 | 结果 |
|---|---|
| 普通 App（MediaProjection） | ❌ 虚拟屏不是 secure 显示 ⇒ secure 层永远不往里投，镜像全黑 |
| `app_process` 以 **shell**（uid 2000）跑 | ❌ `SecurityException: Requires CAPTURE_SECURE_VIDEO_OUTPUT …` |
| **root（uid 0）** | 未验证 —— 本机 LKM 临时 root 不对外开放（`/data/adb` 0700、KSU 管理器显示「未安装」） |

绕过的第一个坑：直接调用会撞 `packageName must match the calling uid`。
解法是「包名可控的 `ContextWrapper` + 反射自建 `DisplayManager`」，包名填 `com.android.shell`。

## 怎么跑

```bash
# 编译（必须 -encoding UTF-8，否则中文注释在 GBK 环境下会炸）
export JAVA_HOME="E:/APPS/android-dev/jdk/jdk-17.0.20.1+1"
javac -encoding UTF-8 -source 8 -target 8 \
      -bootclasspath "E:/APPS/android-dev/sdk/platforms/android-34/android.jar" \
      -d out rootcap/SecureCap.java
# 注意：匿名内部类要一起进 dex，否则 ClassNotFoundException: SecureCap$1
"E:/APPS/android-dev/sdk/build-tools/34.0.0/d8.bat" --min-api 30 --output out \
      out/rootcap/SecureCap.class 'out/rootcap/SecureCap$1.class'
adb push out/classes.dex /data/local/tmp/seccap.dex

# 跑（root 才有意义；shell 会拿到 CAPTURE_SECURE_VIDEO_OUTPUT 的拒绝）
adb shell "CLASSPATH=/data/local/tmp/seccap.dex app_process /system/bin rootcap.SecureCap 1080 944 340 /data/local/tmp/sec.raw"
```

`ListApi.java` 用来打印运行时真实存在的 `createVirtualDisplay` 重载（Android 16 上只有
`(String,int,int,int,Surface,int)` 等 7 个，没有 9 参那版）。换系统版本先跑它，别照抄签名。

## 判据

抓出的 raw 做亮度统计：**全黑（max≈0）= 不行；有内容 = 可行**。
注意 `PT-632`：`ImageReader` 尺寸必须与虚拟屏完全一致，否则一帧都不来。

## 为什么最终没走这条路

本机 secure 页面改用**「搬到外屏」**方案（页面原生跑在 display 1，那里本身就是 secure 显示），
零权限、重启不失效。见 FlipMirror 入口页第 6 个按钮。
