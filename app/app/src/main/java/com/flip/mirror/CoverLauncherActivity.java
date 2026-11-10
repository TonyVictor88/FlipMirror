package com.flip.mirror;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 外屏副屏桌面 —— 让 App 直接**原生**跑在 display 1 上，不走镜像。
 *
 * <h3>为什么这样更好</h3>
 * 镜像方案（MediaProjection → SurfaceView）是把内屏画面搬过来：要双屏并发、
 * 要编码/缩放、内屏一掉就全废，通知栏还会被裁。
 * 而 display 1 本身就是一块**能跑 App 的屏**（三星的 SubHome 就长在上面），
 * 只要用 {@code am start --display 1} 把 App 拉上去，它就是原生渲染：不缩放、不掉帧、不依赖内屏。
 *
 * <h3>关键点：为什么必须用 shell 启动 App</h3>
 * 桌面自己 startActivity 时，目标 App 会落到**默认屏 display 0**（已拆除的内屏，看不见）。
 * FlexUnlock 那个项目是靠 LSPosed hook system_server 解决这一步的；
 * 我们不用 hook —— 直接 shell 执行 {@code am start --display 1}，效果一样（实测可靠）。
 */
public class CoverLauncherActivity extends Activity {

    private static final String TAG = "FlipCoverLauncher";

    private LinearLayout mGrid;
    private TextView mClock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "CoverLauncher on display "
                + (getDisplay() != null ? getDisplay().getDisplayId() : -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E0E12);
        int pad = (int) (10 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, 0);

        // 顶部：时钟 + 刷新
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        mClock = new TextView(this);
        mClock.setTextColor(0xFFFFFFFF);
        mClock.setTextSize(20);
        mClock.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(mClock);

        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setTextSize(11);
        refresh.setAllCaps(false);
        refresh.setOnClickListener(v -> loadApps());
        header.addView(refresh);

        root.addView(header);

        // 应用网格（可滚动）
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        mGrid = new LinearLayout(this);
        mGrid.setOrientation(LinearLayout.VERTICAL);
        sv.addView(mGrid);
        root.addView(sv);

        // 导航键不画在这里：App 一全屏就把桌面盖住，底部键就没了。
        // 改成右上角的系统悬浮层（CoverNavService），永远可见。
        try {
            startForegroundService(new Intent(this, CoverNavService.class));
        } catch (Throwable t) {
            Log.w(TAG, "start CoverNavService failed", t);
        }

        setContentView(root);
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
    }

    private void updateClock() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        mClock.setText(String.format(java.util.Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE)));
    }


    /** 读所有可启动应用，贴成网格。 */
    private void loadApps() {
        mGrid.removeAllViews();
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);

        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) {
                continue;   // 自己不上桌面
            }
            apps.add(ri);
        }
        Collections.sort(apps, (a, b) -> {
            int ra = recentRank(a.activityInfo.packageName);
            int rb = recentRank(b.activityInfo.packageName);
            if (ra != rb) {
                return ra - rb;      // 用过的排前面
            }
            return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
        });

        final int COLS = 4;
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                mGrid.addView(row);
            }
            row.addView(appCell(apps.get(i)));
        }
        Log.i(TAG, "loaded " + apps.size() + " apps");
    }

    /** 一个应用格子：图标 + 名字，点了用 shell 拉到本屏打开。 */
    private View appCell(final ResolveInfo ri) {
        final PackageManager pm = getPackageManager();
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(6, 12, 6, 12);
        cell.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView icon = new ImageView(this);
        Drawable d = ri.loadIcon(pm);
        icon.setImageDrawable(d);
        int s = (int) (40 * getResources().getDisplayMetrics().density);
        icon.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        cell.addView(icon);

        TextView name = new TextView(this);
        name.setText(ri.loadLabel(pm));
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(10);
        name.setMaxLines(1);
        name.setGravity(Gravity.CENTER);
        cell.addView(name);

        cell.setOnClickListener(v -> {
            String pkg = ri.activityInfo.packageName;
            String act = ri.activityInfo.name;
            noteUsed(pkg);   // 记录使用，下次排前面
            // 🔴 必须带 --display 1：桌面自己 startActivity 会落到 display 0（看不见的内屏）
            String cmd = "am start --display 1 -n " + pkg + "/" + act;
            Log.i(TAG, "launch: " + cmd);
            Shell.exec(cmd);
        });
        return cell;
    }

    // ---------- 常用置顶：不需要任何权限，自己记点击 ----------

    private static final String PREFS = "cover_launcher";
    private static final String KEY_RECENT = "recent_pkgs";

    /** 记住最近用过的包（最近的在最前，最多 12 个）。 */
    private void noteUsed(String pkg) {
        try {
            android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            String cur = sp.getString(KEY_RECENT, "");
            java.util.LinkedList<String> list = new java.util.LinkedList<>();
            for (String p : cur.split(",")) {
                if (!p.isEmpty() && !p.equals(pkg)) {
                    list.add(p);
                }
            }
            list.addFirst(pkg);
            while (list.size() > 12) {
                list.removeLast();
            }
            sp.edit().putString(KEY_RECENT, String.join(",", list)).apply();
        } catch (Throwable t) {
            Log.w(TAG, "noteUsed failed: " + t.getMessage());
        }
    }

    /** 返回排序权重：用过的按"最近程度"给 0..N，没用过的一律给大值。 */
    private int recentRank(String pkg) {
        try {
            android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            String cur = sp.getString(KEY_RECENT, "");
            String[] arr = cur.split(",");
            for (int i = 0; i < arr.length; i++) {
                if (arr[i].equals(pkg)) {
                    return i;
                }
            }
        } catch (Throwable ignored) { }
        return 1000;
    }
}
