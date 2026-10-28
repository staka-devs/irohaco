# ADR-0015: 画像の読み込みにCoil 3、動画の再生にMedia3を使う

- 日付: 2026-10-20
- 状態: Accepted
- 関連: ADR-0001（Androidネイティブ）、#5、#15、#100

## Context
グリッドとビューワーで写真を読み込み、ビューワーで動画を再生する。どちらもComposeの画面から使う。

## Decision
- 画像はCoil 3で読み込む。GIFとアニメーションWebPはCoilの`AnimatedImageDecoder`で再生し、フレームはプラットフォームが1枚ずつ復号する。アニメーションかどうかは、ファイルの先頭32バイトで判定する。動画の場面のサムネイルはcoil-videoで、時刻を指定して取り出す。
- 動画はMedia3で再生する。`core:designsystem`の`VideoPlayer`がMedia3のCompose用のMaterial 3のプレイヤーを包み、Media3の不安定なAPIのopt-inをその中に閉じる。再生用のExoPlayerはビューワーのViewModelが持つ。
- デコーダーが起動に失敗したときは、別のデコーダーで試し直す。

## Consequences
画像と動画の読み込みを、Composeの部品として扱える。Media3の不安定なAPIが変わっても、直すのは`VideoPlayer`だけで済む。アニメーションWebPはファイルを読まないと静止画と見分けられないため、グリッドに印を付けていない。
