#!/usr/bin/env bash
# ロック画面(PIN)を設定した端末の再起動を、エミュレーターで確かめる結合テスト(層 3)。
#
#   ./gradlew :app:assembleDebug
#   scripts/e2e-reboot.sh                # 既定は emulator-5554。約 3〜4 分かかる
#
# 確かめること:
#   - 再起動後、ロックを解除する前に VPN が回復する(LOCKED_BOOT_COMPLETED)
#   - ロック中の名前解決が記録され(DE の一時バッファ)、解除後にログへ取り込まれる
#   - 対象外ドメインは、ロック中も記録されない
#
# 注意: 端末の再起動と、PIN(1234)の設定・解除を行う。終了時(失敗しても)PIN は解除する。
#       デバッグ版(com.github.rockyx2000.dnslogger.debug)を使い、リリース版には触れない。
set -u
SERIAL="${ADB_SERIAL:-emulator-5554}"
PKG=com.github.rockyx2000.dnslogger.debug
PIN=1234
APK="$(dirname "$0")/../app/build/outputs/apk/debug/app-debug.apk"
command -v adb >/dev/null || export PATH="${ANDROID_HOME:-$HOME/android-dev/sdk}/platform-tools:$PATH"
a() { timeout 40 adb -s "$SERIAL" "$@"; }

pass=0; fail=0
ok()   { echo "  ✔ $1"; pass=$((pass+1)); }
ng()   { echo "  ✘ $1"; fail=$((fail+1)); }
check() { local desc="$1"; shift; if "$@"; then ok "$desc"; else ng "$desc"; fi; }
wait_for() { local n="$1"; shift; for _ in $(seq 1 "$n"); do "$@" && return 0; sleep 1; done; return 1; }

DE="/data/user_de/0/$PKG"
tun_up()     { a shell ip -o addr show 2>/dev/null | grep -q "10.0.0.2/32"; }
booted()     { [ "$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }
locked()     { a shell dumpsys user 2>/dev/null | grep -q "State: RUNNING_LOCKED"; }
unlocked()   { a shell dumpsys user 2>/dev/null | grep -q "State: RUNNING_UNLOCKED"; }
ce_readable(){ a shell run-as "$PKG" ls files/ >/dev/null 2>&1; }       # run-as は、ロック中は CE を読めず失敗する
logged()     { a shell run-as "$PKG" cat files/dns_access.log 2>/dev/null | cut -f2 | grep -qx "$1"; }
no_pending() { ! a shell run-as "$PKG" ls "$DE/files/" 2>/dev/null | grep -q pending; }
resolves()   { local o; o=$(a shell ping -c1 -W5 "$1" 2>&1); echo "$o" | grep -q "^PING " && ! echo "$o" | grep -qi "unknown host"; }
cleanup()    { a shell locksettings clear --old "$PIN" >/dev/null 2>&1; }
trap cleanup EXIT

echo "== 準備 ($SERIAL)"
[ -f "$APK" ] || { echo "APK がありません。先に ./gradlew :app:assembleDebug を実行してください"; exit 2; }
a get-state >/dev/null 2>&1 || { echo "端末 $SERIAL に接続できません"; exit 2; }
a uninstall "$PKG" >/dev/null 2>&1
a install -r "$APK" >/dev/null 2>&1 || { echo "インストールに失敗しました"; exit 2; }
a shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
a shell cmd deviceidle whitelist +"$PKG" >/dev/null 2>&1
a shell appops set "$PKG" ACTIVATE_VPN allow >/dev/null 2>&1
a shell "run-as $PKG sh -c 'mkdir -p $DE/shared_prefs && cat > $DE/shared_prefs/filter.xml'" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="domains">example.com</string>
</map>
XML
a shell am start -n "$PKG/com.github.rockyx2000.dnslogger.MainActivity" >/dev/null 2>&1
check "初回起動で自動開始する(再起動前の状態づくり)" wait_for 40 tun_up
a shell run-as "$PKG" rm -f files/dns_access.log
a shell locksettings set-pin "$PIN" >/dev/null 2>&1
a shell sync; sleep 10

echo "== 再起動(ロックは解除しない)"
a reboot >/dev/null 2>&1; sleep 30
check "起動が完了する" wait_for 240 booted
check "端末がロックされている(RUNNING_LOCKED)" wait_for 30 locked
check "ロックを解除する前に VPN が回復する" wait_for 120 tun_up

echo "== ロック中の名前解決"
check "対象ドメインが名前解決できる(www.example.com)" wait_for 60 resolves www.example.com
check "対象外ドメインが名前解決できる(example.net)" resolves example.net

echo "== ロック解除"
a shell input keyevent KEYCODE_WAKEUP; sleep 2
a shell input swipe 540 1900 540 600 300; sleep 3
a shell input text "$PIN"; sleep 1; a shell input keyevent KEYCODE_ENTER
check "ロックが解除される(RUNNING_UNLOCKED)" wait_for 60 unlocked
check "解除後にアプリの保存領域を読める" wait_for 60 ce_readable
check "ロック中の記録がログへ取り込まれる(www.example.com)" wait_for 90 logged www.example.com
check "取り込み後、一時バッファは消える" wait_for 30 no_pending
if logged example.net; then ng "対象外ドメインは記録されない"; else ok "対象外ドメインは記録されない"; fi
check "解除後も VPN は動いている" tun_up

echo
echo "結果: ${pass} 件成功 / ${fail} 件失敗"
[ "$fail" -eq 0 ]
