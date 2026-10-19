# ADR-0001: AndroidネイティブとKotlin-firstを採用
- 状態: Accepted
- 日付: 2026-10-18

## Context
基準端末上での実機検証を前提とし、AndroidのMediaStore、SAF、ギャラリー表示、Material 3 Expressive、LiteRT-LMとの密な連携が必要。UI/写真アクセスまでRustに寄せるとJNIや別UI基盤の負担が増える。

## Decision
UI・Application・永続化連携はKotlin/Jetpack Compose/Room。Rustは計測でCPUネイティブ最適化が必要と判明した箇所のみ利用する。推論はLiteRT-LMのBackendを用いる。

## Consequences
Android APIへの追従とUIの開発を優先できる。iOS展開は別UIを想定する。Rust導入は性能測定とFFIコスト計測を必須条件とする。
