# ADR-0002: ローカルファーストとし、外部APIは許可制にする
- 状態: Accepted
- 日付: 2026-10-18

## Context
写真、動画、GPS、個人的なメモは機微性が高い。一方で、OpenAI Chat Completions、Responses、Anthropic Messages、OpenAI Embeddings、Gemini Embeddingsを任意で使えるようにしたい。

## Decision
推論はローカルを既定とする。外部APIは登録しているかどうかにかかわらず既定で無効にする。`egress_grants`で、接続先、タスク、送る内容の種類の組み合わせを明示的に許可し、行がなければ拒否する。無断でクラウドに切り替えない。APIキーはKeystoreで保護した秘密情報の保存領域に置き、DBには参照だけを入れる。

## Consequences
通信が起きない標準の動作を検証しやすい。外部のEmbeddingで全部の写真を送る前に、件数、内容、送信先を確認してもらう必要がある。ローカルの互換サーバーにも同じ許可の確認を適用する。
