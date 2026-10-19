# ADR-0003: Embedding空間を別々に保持し、すぐに切り替えられるようにする
- 状態: Accepted
- 日付: 2026-10-18

## Context
異なるEmbeddingモデルや前処理で作ったベクトルは、次元数が同じでも互いに検索には使えない。モデルを切り替えるたびに既存の索引を捨てると、実用にならない。

## Decision
モデルのアーティファクト、次元数、正規化、プレフィックス、前処理の署名を含む、変更しない`embedding_spaces`を設け、READYの空間を複数保持する。`search_profile_spaces`の変更はトランザクションで行い、新しい検索から使う。ベクトルは `(space_id,target_id,source_revision_digest)` ごとに変更せずに保持する。新しいspaceはBUILDING、VERIFYING、READYの順に進めてから既定に切り替える。モデルをまたいでスコアを直接比べず、RRFで統合する。

## Consequences
ディスクの使用量は増える。古いREADYの索引を勝手にGCしない。空間を更新するときは、設定の列をUPDATEせず、新しいIDを作る。
