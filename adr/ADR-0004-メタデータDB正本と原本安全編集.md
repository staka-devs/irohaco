# ADR-0004: メタデータはDBの値を正とし、原本の編集は明示的な操作にする
- 状態: Accepted
- 日付: 2026-10-18

## Context
JPEG、HEIC、RAW、HDRやMotion Photoなどでは、安全に書き戻せるメタデータの形式が異なる。ユーザーの評価とメモは、SDカードを取り外しても残したい。

## Decision
メタデータの観測値は`metadata_observations`に、明示的な上書きと削除の指定は`metadata_override_sets`と`metadata_override_values`に分けて持つ。原本へ反映するときは、権限の確認、作業用コピーと復旧手段の用意、書き込み、読み込み直しての照合、コミットという手順をジャーナル付きで行う。AIが推定したGPSは自動で書き込まない。写真のデータの削除と、アプリのDBの注釈の削除は連動させない。

## Consequences
ファイルとDBは1つのトランザクションにできないので、`metadata_write_operations`で復旧する。原本への書き込みに対応していない形式でも、DB上の編集は使える。
