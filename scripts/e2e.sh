#!/usr/bin/env bash
# エミュレーター上の結合テスト(層 3)。起動 → VPN → 名前解決 → 記録 → Wi-Fi 切断 → 強制終了と復帰 → 停止。
#
#   ./gradlew :app:assembleDebug
#   scripts/e2e.sh                       # 既定は emulator-5554
#   ADB_SERIAL=emulator-5556 scripts/e2e.sh
#
# 注意:
#   - デバッグ版(com.github.rockyx2000.dnslogger.debug)を使う。リリース版とは別アプリで、データも別。
#   - 同時に VPN になれるのは 1 つだけ。リリース版が記録中なら、このテストで解除される。
#   - 名前解決に外部ネットワークを使う。実在するドメイン(example.com / www.iana.org / data.iana.org / example.net)を使う。
#     同じ FQDN は 60 秒以内なら 1 回とみなされる(重複抑制)ので、手順ごとに別の FQDN を使う。
set -u
SERIAL="${ADB_SERIAL:-emulator-5554}"
PKG=com.github.rockyx2000.dnslogger.debug
APK="$(dirname "$0")/../app/build/outputs/apk/debug/app-debug.apk"
command -v adb >/dev/null || export PATH="${ANDROID_HOME:-$HOME/android-dev/sdk}/platform-tools:$PATH"
a() { adb -s "$SERIAL" "$@"; }

pass=0; fail=0
ok()   { echo "  ✔ $1"; pass=$((pass+1)); }
ng()   { echo "  ✘ $1"; fail=$((fail+1)); }
check() { local desc="$1"; shift; if "$@"; then ok "$desc"; else ng "$desc"; fi; }
# wait_for 秒数 コマンド... : 条件が真になるまで 1 秒ごとに確かめる
wait_for() { local n="$1"; shift; for _ in $(seq 1 "$n"); do "$@" && return 0; sleep 1; done; return 1; }

tun_up()      { a shell ip -o addr show 2>/dev/null | grep -q "10.0.0.2/32"; }
tun_down()    { ! tun_up; }
logfile()     { a shell run-as "$PKG" cat files/dns_access.log 2>/dev/null; }
logged()      { logfile | cut -f2 | grep -qx "$1"; }
# 別名(CNAME)の名前は、ping が正式名を表示する。名前解決できたかどうかは「unknown host」の有無で見る
resolves()    { local o; o=$(a shell ping -c1 -W5 "$1" 2>&1); echo "$o" | grep -q "^PING " && ! echo "$o" | grep -qi "unknown host"; }
# 状態・生存確認・対象ドメインは、端末保護ストレージ(ロック解除前から使える領域)にある
DE="/data/user_de/0/$PKG/shared_prefs"
health()      { a shell run-as "$PKG" cat "$DE/health.xml" 2>/dev/null; }
focus()       { a shell dumpsys window 2>/dev/null | grep -i mCurrentFocus; }
tap_text() {  # 画面上の text が完全一致する要素の中央をタップする。見つかるまで最大 20 秒再試行する
  local c n
  for n in $(seq 1 20); do
    a shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
    c=$(a shell cat /sdcard/u.xml 2>/dev/null | python3 -c "
import sys,re
x=sys.stdin.read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',x):
    if m.group(1)==sys.argv[1]: print((int(m.group(2))+int(m.group(4)))//2,(int(m.group(3))+int(m.group(5)))//2); break
" "$1")
    if [ -n "$c" ]; then a shell input tap $c; return 0; fi
    sleep 1
  done
  return 1
}
launch()      { a shell am start -n "$PKG/com.github.rockyx2000.dnslogger.MainActivity" >/dev/null 2>&1; }
screen_has()  { a shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; a shell cat /sdcard/u.xml | grep -q "$1"; }
gap_recorded(){ health | grep -q "name=\"gaps\">$1,"; }           # 途切れの先頭が、指定した生存時刻
beat_cleared(){ health | grep -q 'name="last_beat" value="0"'; }
resolves_and_logged() { resolves "$1" && wait_for 10 logged "$1"; }

echo "== 準備 ($SERIAL)"
[ -f "$APK" ] || { echo "APK がありません。先に ./gradlew :app:assembleDebug を実行してください"; exit 2; }
a get-state >/dev/null 2>&1 || { echo "端末 $SERIAL に接続できません"; exit 2; }
wait_for 60 test "$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 || { echo "端末の起動が完了していません"; exit 2; }
a uninstall "$PKG" >/dev/null 2>&1
a install -r "$APK" >/dev/null 2>&1 || { echo "インストールに失敗しました"; exit 2; }
a shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
a shell cmd deviceidle whitelist +"$PKG" >/dev/null 2>&1          # 電池の除外ダイアログを出さない
a shell appops set "$PKG" ACTIVATE_VPN allow >/dev/null 2>&1       # VPN の同意ダイアログを出さない
a shell "run-as $PKG sh -c 'mkdir -p $DE && cat > $DE/filter.xml'" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="domains">example.com,iana.org</string>
</map>
XML
a shell am force-stop "$PKG"

echo "== 1. 初回起動で自動開始"
launch
check "開始ボタンを押さなくても VPN が確立する(10.0.0.2/32 のインタフェース)" wait_for 30 tun_up
check "画面が「記録中」になる" wait_for 30 screen_has '記録中'

echo "== 2. 記録対象の判定"
check "対象ドメインが名前解決できる" resolves example.com
check "対象ドメインが記録される" wait_for 10 logged example.com
check "対象外ドメインが名前解決できる(転送されている)" resolves example.net
sleep 2
if logged example.net; then ng "対象外ドメインは記録されない"; else ok "対象外ドメインは記録されない"; fi

echo "== 3. Wi-Fi の切断と再接続"
a shell svc wifi disable; sleep 4
a shell svc wifi enable
check "再接続後も VPN が残っている" tun_up
check "再接続後に名前解決できる" wait_for 40 resolves www.iana.org
check "再接続後も記録が続く(www.iana.org)" wait_for 10 logged www.iana.org

echo "== 4. 強制終了と復帰"
old=$(( $(date +%s) * 1000 - 3600000 ))                            # 最後の生存確認を 1 時間前にしておく
a shell "run-as $PKG sed -i 's/name=\"last_beat\" value=\"[0-9]*\"/name=\"last_beat\" value=\"$old\"/' $DE/health.xml" 2>/dev/null
pid=$(a shell pidof "$PKG" | tr -d '\r')
a shell run-as "$PKG" kill -9 "$pid" 2>/dev/null
check "プロセスが落ちると VPN が消える" wait_for 15 tun_down
launch
check "アプリを開くと自動で再開する" wait_for 30 tun_up
check "途切れが記録される(最後の生存確認が gaps に入る)" gap_recorded "$old" || { echo "    --- health.xml"; health | sed 's/^/    /'; }
check "再開後に名前解決と記録が続く(data.iana.org)" wait_for 30 resolves_and_logged data.iana.org

echo "== 5. 停止"
launch
tap_text 停止
check "停止すると VPN が消える" wait_for 15 tun_down
check "画面が「停止中」になる" wait_for 30 screen_has '停止中'
check "停止中も名前解決はできる(VPN を介さない)" resolves www.example.com
sleep 2
if logged www.example.com; then ng "停止中は記録されない"; else ok "停止中は記録されない"; fi
check "ユーザー停止は途切れとして数えない(最後の生存確認が消える)" beat_cleared

echo "== 6. 停止した後は、アプリを開き直しても自動では開始しない"
a shell input keyevent KEYCODE_HOME; sleep 2
launch; sleep 10
check "開き直しても VPN は起動しない" tun_down
tap_text 開始
check "「開始」を押せば再開する" wait_for 20 tun_up

echo
echo "結果: ${pass} 件成功 / ${fail} 件失敗"
[ "$fail" -eq 0 ]
