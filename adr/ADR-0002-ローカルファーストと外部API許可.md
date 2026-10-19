# ADR-0002: ローカルファーストと外部API許可
- 状態: Accepted
- 日付: 2026-10-18

## Context
写真、動画、GPS、プライベートメモは高い機微性を持つ。一方でOpenAI Chat Completions、Responses、Anthropic Messages、OpenAI Embeddings、Gemini Embeddingsを任意で使いたい。

## Decision
ローカル推論が既定。外部APIは未登録/登録済みにかかわらず既定無効。`egress_grants`で接続先×タスク×ペイロード種別を明示許可する。行がなければ拒否。無断でクラウドにフォールバックしない。APIキーはKeystore保護の秘密保存領域に保管しDBには参照のみ格納。

## Consequences
通信が発生しない標準動作を検証しやすい。外部Embeddingで全写真を送信する前に件数・内容・通信先の確認が必要になる。ローカル互換サーバーにも同じゲートを適用する。
