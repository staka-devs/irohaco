# ADR-0004: メタデータはDB正本とし原本編集を明示操作にする
- 状態: Accepted
- 日付: 2026-10-18

## Context
JPEG、HEIC、RAW、HDR/Motion Photo等では安全に書き戻せるメタデータ形式が異なる。ユーザーの評価・メモはSDカード取り外しでも保持したい。

## Decision
メタデータの観測値は`metadata_observations`、明示上書き/削除指定は`metadata_override_sets`＋`metadata_override_values`に分ける。原本へ反映する場合は権限確認→作業コピー/復旧手段→書込→再読込照合→コミットのジャーナル手順を使う。AI推定GPSは自動書込しない。写真データの削除とアプリDBの注釈削除を連動させない。

## Consequences
ファイルとDBは単一トランザクションにできないため`metadata_write_operations`で復旧する。原本への書込み非対応形式でもDB上の編集を利用できる。
