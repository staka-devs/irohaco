# ADR-0021: ローカルのEmbeddingにEmbeddingGemma 2をLiteRT-LMで動かす

- 日付: 2026-10-22
- 状態: Accepted
- 関連: ADR-0002（ローカルファースト）、ADR-0003、ADR-0006、PRD MOD-002〜004、ARCHITECTURE §10、AI_SEARCH_SPIKE、#24、#28

## Context
写真と動画の場面を、文で書いた内容で探したい（ADR-0006）。推論は端末の中で行う（ADR-0002）。文と画像を同じ空間に写すEmbeddingのモデルと、Androidで動かすランタイムを決める必要がある。

## Decision
- 既定のEmbeddingのモデルは、EmbeddingGemma 2 Text-Vision 440M（Apache-2.0）にする。文と画像を同じ空間に写す。動画は抽出したフレームを画像として入力する。
- ランタイムはLiteRT-LM（`litertlm-android`）にし、`infrastructure:local-ai`の`LiteRtEmbeddingRuntime`の中に閉じる。ベクトルは正規化して返す。
- モデルは`AiResourceManager`だけが持つ。アプリの起動では読み込まず、最初の推論の要求で読み込む。推論は1件ずつ、検索、RAG、編集、バックグラウンドの索引の優先度の順に通す。60秒使われないときと、メモリの逼迫を通知されたときに解放する。
- 検索の文には空間の接頭辞を付けてからベクトルにする。

## Consequences
文と画像を1つのモデルで扱えるので、写真、動画の場面、検索用メモを同じ空間で探せる。モデルを1つしか読み込まないので、メモリの上限を見積もりやすい。精度、速さ、メモリは、Spike Aの計測（AI_SEARCH_SPIKE）で実機で確かめる。EmbeddingGemma 2のフル構成（740M）で動画を直接入力する方法は、モデルの配布物とランタイムが対応していると確かめてから検討する。
