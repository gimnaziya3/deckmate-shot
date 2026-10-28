#!/usr/bin/env bash
# Статические проверки безопасности по исходникам. Каждая строка PASS или FAIL; при любом FAIL сборка останавливается.
cd "$(dirname "$0")/.."
bad=0
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; bad=1; }
none() { if grep -rnE "$2" $3 >/dev/null 2>&1; then fail "$1"; grep -rnE "$2" $3 | head -5; else pass "$1"; fi; }
has() { if grep -rqE "$2" $3; then pass "$1"; else fail "$1"; fi; }

SRC="app/src/main app/src/fast"
MAN="app/src/main/AndroidManifest.xml"

none "2. нет журналирования (ключ не попадёт в логи)" 'android\.util\.Log|Log\.[dviwe]\(|println\(|printStackTrace|Timber' "$SRC"
none "2. приложение не пишет в буфер обмена" 'ClipboardManager|setPrimaryClip' "$SRC"
has  "2. резервные копии выключены" 'android:allowBackup="false"' "$MAN"
has  "2. правила извлечения данных исключают всё" '<exclude domain="sharedpref"' "app/src/main/res/xml/data_extraction_rules.xml"
has  "2. ключ шифруется AES-GCM в Android Keystore" 'AndroidKeyStore' "app/src/main/java/app/deckmate/shot/SecureStore.kt"
has  "1. cleartext запрещён" 'cleartextTrafficPermitted="false"' "app/src/main/res/xml/network_security_config.xml"
has  "1. код отказывается слать на адрес без https" 'startsWith\("https://"\)' "app/src/main/java/app/deckmate/shot/Uploader.kt"
none "5. скриншот не пишется на диск" 'FileOutputStream|createTempFile|openFileOutput|getExternalFilesDir|FileWriter|MediaStore\.Images\.Media\.insert|\.insert\(' "$SRC"
none "10. нет аналитики и сторонних SDK" 'firebase|crashlytics|appsflyer|amplitude|sentry|mixpanel|admob|facebook' "app/build.gradle.kts $SRC"
has  "7. оверлей не принимает касания" 'FLAG_NOT_TOUCHABLE' "app/src/main/java/app/deckmate/shot/OverlayController.kt"
has  "7. оверлей по умолчанию выключен" 'getBoolean\("ov_on", false\)' "app/src/main/java/app/deckmate/shot/SecureStore.kt"
none "4. спецвозможности не читают содержимое окон" 'canRetrieveWindowContent|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews|typeAllMask' "app/src/fast/res/xml"
has  "4. служба плитки защищена системным разрешением" 'BIND_QUICK_SETTINGS_TILE' "$MAN"

# компоненты: список exported
python - <<'PY'
import re,sys
m=open("app/src/main/AndroidManifest.xml",encoding="utf-8").read()
f=open("app/src/fast/AndroidManifest.xml",encoding="utf-8").read()
want={".MainActivity":"false",".TriggerActivity":"true",".CaptureService":"false",".WatchService":"false",".ShotTile":"true"}
ok=True
for name,val in want.items():
    mm=re.search(r'<(?:activity|service)\s+android:name="'+re.escape(name)+r'"(.*?)>',m,re.S)
    if not mm or f'android:exported="{val}"' not in mm.group(1):
        print("FAIL  4. exported",name,"должно быть",val); ok=False
if 'android:exported="true"' in f and "BIND_ACCESSIBILITY_SERVICE" not in f:
    print("FAIL  4. спецвозможности без системного разрешения"); ok=False
if ok: print("PASS  4. exported совпадает с ожидаемым списком")
sys.exit(0 if ok else 1)
PY
[ $? -eq 0 ] || bad=1
exit $bad
