# Architecture Decision Records

このアプリの設計判断の記録です。形式は「背景 → 決定 → 結果(得たもの・失ったもの)→ 検討した代替案」です。
決定を変えるときは、既存の ADR を書き換えず、新しい ADR を追加して旧 ADR を `置き換え済み` にします。

| # | 決定 | 状態 |
| --- | --- | --- |
| [0001](0001-local-vpn-for-dns-only.md) | DNS だけを横取りするローカル VPN で記録する | 採用 |
| [0002](0002-minimize-collected-data.md) | 記録するデータを最小にする(指定 FQDN のみ・端末内保存) | 採用 |
| [0003](0003-daily-summary-via-discord.md) | 毎日 23:00 に Discord Webhook へ集計を送る | 採用 |
| [0004](0004-foreground-service-and-power.md) | フォアグラウンドサービスで常駐し、省電力の除外を案内する | 採用 |
| [0005](0005-follow-upstream-dns.md) | 上流 DNS をネットワークの変化に追従させる | 採用 |
| [0006](0006-detect-and-report-gaps.md) | 記録の途切れを検知してサマリで報告する | 採用 |
| [0007](0007-no-dependencies.md) | 外部ライブラリを使わず Android 標準 API だけで作る | 採用 |
| [0008](0008-flat-ui-and-theming.md) | UI はフラットな道具風にし、ライト / ダークに対応する | 採用 |
| [0009](0009-distribution.md) | 配布方法: パッケージ名・署名・最小 OS・ライセンス | 採用 |
