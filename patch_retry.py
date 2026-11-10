import io

p = 'H:/AI PG/FlipMirror/app/app/src/main/java/com/flip/mirror/MirrorActivity.java'
s = io.open(p, encoding='utf-8').read()

# 1) 失败时自动重试一次（拿新授权）
old = '''        } catch (MirrorEngineImpl.MirrorException e) {'''
new = '''        } catch (MirrorEngineImpl.MirrorException e) {
            // 授权残留是最常见的一次性故障：静态通道里还是上一轮用过的 resultData，
            // 系统会拒绝复用。处理办法不是让用户手动重来，而是自动重新申请一次干净授权。
            if (!mRetried) {
                mRetried = true;
                Log.i(TAG, "stale projection -> auto retry with fresh grant");
                sMirroring = false;
                Shell.exec("am start --display 1 -n com.flip.mirror/.MainActivity --ez auto_start true");
                finish();
                return;
            }'''
assert s.count(old) == 1, 'catch anchor'
s = s.replace(old, new, 1)

# 2) 字段
if 'mRetried' not in s.replace('mRetried = true', ''):
    pass
if 'private boolean mRetried' not in s:
    s = s.replace('    private boolean mTakeover = false;',
                  '    private boolean mTakeover = false;\n    /** 授权失败后是否已自动重试过一次（防死循环）。 */\n    private boolean mRetried = false;', 1)

# 3) 静态通道一次性消费：读走就清，避免下一轮拿到旧令牌
old3 = '''            resultCode = sResultCode;
            resultData = sResultData;'''
new3 = '''            resultCode = sResultCode;
            resultData = sResultData;
            // 一次性消费：留着只会让下一次启动拿到已失效的令牌
            sResultCode = 0;
            sResultData = null;'''
if old3 in s:
    s = s.replace(old3, new3, 1)
    print('statics consumed once')
else:
    print('statics anchor not found')

io.open(p, 'w', encoding='utf-8').write(s)
print('MirrorActivity retry patched')
