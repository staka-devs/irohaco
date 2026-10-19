# ADR-0001: AndroidネイティブとKotlin-firstを採用する
- 状態: Accepted
- 日付: 2026-10-18

## Context
基準端末での実機検証を前提にしており、AndroidのMediaStore、SAF、ギャラリーの表示、Material 3 Expressive、LiteRT-LMと密に連携する必要がある。UIや写真へのアクセスまでRustで書くと、JNIや別のUI基盤の負担が増える。

## Decision
UI、Application、永続化との連携はKotlin、Jetpack Compose、Roomで作る。Rustは、計測でCPU向けのネイティブ最適化が必要だと分かった箇所だけに使う。推論にはLiteRT-LMのBackendを使う。

## Consequences
Android APIの変更への追従とUIの開発を優先できる。iOSに展開するなら、UIは別に作る想定とする。Rustを入れるには、性能の測定とFFIのコストの計測を必須の条件とする。
