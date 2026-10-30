# ADR-0032: 外部APIは形式ごとのアダプターで今の推論の口に合わせ、使えるかどうかは確かめたモデルの能力で決める

- 日付: 2026-10-31
- 状態: Proposed
- 関連: ADR-0002（ローカルファースト）、ADR-0003、ADR-0021、ADR-0031、API-001〜008、AC-009、ARCHITECTURE §4.2、§9、#49

## Context
外部のAPIは5つの形式に対応する（ARCHITECTURE §9.1）。生成はOpenAI Chat Completions、OpenAI Responses、Anthropic Messagesで、EmbeddingはOpenAI EmbeddingsとGemini embedContentである。同じ形式でも、画像を入力できるか、答えを少しずつ返せるかはモデルによって違う。互換サーバーでは形式の名前から能力を推定できない（§4.2）。

アプリの中では、検索と索引は`EmbeddingSession`の`embedText`と`embedImage`を、質問への答えは`AnswerSession`の`answer`を使っている。どちらもローカルのLiteRT-LMで実装している。DBには接続先（`provider_connections`）、モデル（`model_profiles`）、能力のコード（`model_capabilities`）、用途ごとのモデル（`task_model_assignments`）、通信の許可（`egress_grants`）の表がある。

## Decision
- 外部のAPIは形式ごとに1つのアダプターで、今の`EmbeddingSession`と`AnswerSession`を実装する。検索、索引、質問のユースケースは、ローカルか外部かを区別しない。アダプターは新しいモジュール`infrastructure:remote-ai`に置く。
- 通信には今のモデルのダウンロードと同じ`HttpURLConnection`を、JSONにはAndroidの`org.json`を使い、ライブラリを足さない。答えを少しずつ返す形式（Server-Sent Events）は、形式ごとのイベントの名前に合わせて読む。
- 能力は`model_capabilities`にコードで持つ。使うコードは次の6つで、アプリが使わない能力（JSONスキーマ、ツール）は持たない。
  - `INPUT_TEXT`、`INPUT_IMAGE`、`INPUT_VIDEO`
  - `OUTPUT_TEXT`、`OUTPUT_EMBEDDING`
  - `STREAMING`
- 能力は、利用者が登録するときに選び、接続テストで確かめたものだけを保存する。接続テストは利用者の操作で行い、アプリが持つサンプルの文と画像を送る。形式の名前やモデルIDから能力を決めない。
- 用途に割り当てられるのは、その用途に要る能力をすべて持つモデルだけにする。
  - 写真の索引と検索（`IMAGE_INDEX`、`SEARCH_QUERY`）: 同じモデルが`INPUT_TEXT`、`INPUT_IMAGE`、`OUTPUT_EMBEDDING`を持つ。文と画像を同じ空間に写せるモデルでなければ、文で写真を探せないためである。
  - 動画の索引（`VIDEO_INDEX`）: 写真と同じモデルで、フレームを画像として送る（ADR-0024）。`INPUT_VIDEO`は持っていても、R0では使わない。
  - 質問への答え（`RAG`）: `INPUT_TEXT`、`INPUT_IMAGE`、`OUTPUT_TEXT`を持つ。`STREAMING`がなければ、答えは全部そろってから出す。
- 外部のEmbeddingはモデルごとに別の`embedding_spaces`を作り、ローカルの空間と混ぜない（ADR-0003）。次元は接続テストの応答から決める。APIがモデルの版を返さないときは、`artifact_revision`を空にして、索引を作るときに中身が変わりうることを示す。
- アダプターは送る前に毎回`EgressPolicy`の許可を確かめ、許可がなければ送らない。ローカルと外部の間でも、外部の接続先の間でも、失敗したときに別のモデルへ自動で切り替えない（AC-009）。
- 1回の呼び出しで送れる件数と大きさは、形式ごとにアダプターの中で決めた値を使い、DBには持たない。
- 形式ごとの契約テストでは、アプリが送る要求と、決めておいた応答の読み方を、JVMのテストの中で立てたHTTPサーバーで確かめる。本物のAPIにはつながない。

## Consequences
ユースケースと画面は、ローカルのモデルと同じ口で外部のモデルを使える。新しい形式を足すときは、アダプターと契約テストを足せばよい。

能力を確かめるのは登録したときだけなので、サーバー側でモデルが変わると、使えない能力が残ることがある。そのときは通信のエラーとして示し（API-008）、接続テストをやり直してもらう。文だけを入力できるEmbeddingのモデルは、R0では写真の検索に使えない。
