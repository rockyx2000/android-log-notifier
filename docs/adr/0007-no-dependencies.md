# 0007. 外部ライブラリを使わず Android 標準 API だけで作る

- 状態: 採用
- 日付: 2026-10-07

## 背景

このアプリは、VPN の常駐、アラーム、HTTP の POST、簡単な画面だけでできている。
権限が強い(VPN で DNS を見る)アプリなので、第三者のコードを増やすほど、確認すべき範囲が広がる。

## 決定

- `android.useAndroidX=false`。`app/build.gradle.kts` に `dependencies` を持たない。
- HTTP は `HttpURLConnection`、JSON は `org.json`、画面はフレームワークの `Activity` とビューで作る。
- スケジューリングは、`WorkManager` ではなく `AlarmManager` を使う(時刻の精度のため。→ [0003](0003-daily-summary-via-discord.md))。

## 結果

得たもの

- コードの全体が小さく、読み切れる。権限の強いアプリとして、監査しやすい。
- 依存の更新や、脆弱性への追従が要らない。
- ビルドが速く、APK も小さい。

失ったもの

- Material コンポーネントなどが使えず、UI は自前のスタイルで作る(→ [0008](0008-flat-ui-and-theming.md))。
- `onActivityResult` など、非推奨の API を使う箇所がある。

## 検討した代替案

- **AndroidX / Material / OkHttp を導入する**: 便利だが、このアプリの規模では得るものが少ない。
