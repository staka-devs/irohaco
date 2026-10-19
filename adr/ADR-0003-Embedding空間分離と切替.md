# ADR-0003: Embedding空間を独立保持し即時切替可能にする
- 状態: Accepted
- 日付: 2026-10-18

## Context
異なるEmbeddingモデルや前処理のベクトルは次元数が同じでも検索互換ではない。モデル切替の度に既存索引を破棄すると実用性が低い。

## Decision
モデルアーティファクト、次元数、正規化、プレフィックス、前処理署名を含む不変の`embedding_spaces`を設ける。複数のREADY空間を保持する。`search_profile_spaces`の変更をトランザクション化し、新規検索から採用する。ベクトルは `(space_id,target_id,source_revision_digest)` ごとに不変保持。新spaceをBUILDING→VERIFYING→READYにしてから既定切替。モデル間のスコア直接比較をせずRRFで統合する。

## Consequences
ディスク占有は増える。古いREADY索引を勝手にGCしない。空間の更新は設定列のUPDATEではなく新しいIDを作成する。
