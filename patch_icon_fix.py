import io

p = 'H:/AI PG/FlipMirror/app/app/src/main/AndroidManifest.xml'
s = io.open(p, encoding='utf-8').read()

# 1) 磁贴服务恢复成系统图标，去掉误插的 roundIcon
s = s.replace('''            android:name=".MirrorTileService"
            android:exported="true"
            android:icon="@drawable/ic_launcher_flipmirror"
        android:roundIcon="@drawable/ic_launcher_flipmirror"
            android:label="投屏到外屏"''',
'''            android:name=".MirrorTileService"
            android:exported="true"
            android:icon="@android:drawable/ic_menu_slideshow"
            android:label="投屏到外屏"''', 1)

# 2) application 标签加上应用图标
s = s.replace('''    <application
        android:allowBackup="false"
        android:label="FlipMirror"''',
'''    <application
        android:allowBackup="false"
        android:icon="@drawable/ic_launcher_flipmirror"
        android:roundIcon="@drawable/ic_launcher_flipmirror"
        android:label="FlipMirror"''', 1)

io.open(p, 'w', encoding='utf-8').write(s)
print('manifest fixed')
