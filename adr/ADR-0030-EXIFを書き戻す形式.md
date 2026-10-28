# ADR-0030: 原本に書き戻すのはJPEGのEXIFだけにし、ほかの形式はDBとコピーで扱う

- 日付: 2026-10-29
- 状態: Proposed
- 関連: ADR-0004（原本の編集は明示的な操作）、ADR-0029、MET-003、AC-008、R-05、#42、#45

## Context
ADR-0004で、原本の編集は明示的な操作にし、書き込んだあとに読み込み直して照合すると決めた。どの形式に書き込めるかは決めていない。アプリはEXIFの読み取りにAndroidXのExifInterfaceを使っている。ExifInterfaceが書き込めるのはJPEG、PNG、WebPで、HEIC、AVIF、RAW、動画には書き込めない。ExifInterfaceはEXIFの領域を作り直すだけで、XMPの中身は項目ごとに書き換えられない。

JPEGでも、Motion Photo（後ろに動画を付けたもの）と、HDRのゲインマップを持つものは、EXIFの領域の大きさが変わると、後ろのデータを指す位置がずれるおそれがある。PNGとWebPのEXIFは、読めないアプリが多い。

## Decision
- 原本に書き戻すのは、JPEGのうち、`MediaFormat`がMotion PhotoでもHDRでもないものに限る。
- 書き戻す項目はExifInterfaceのタグで書ける次の3つに限る。
  - 撮影日時: `DateTimeOriginal`と`OffsetTimeOriginal`
  - GPS: 緯度、経度、高度と、それぞれの向き
  - 説明: `ImageDescription`
- タグ、評価、メモはXMPに入るので、原本には書かず、DBにだけ持つ。
- ほかの形式（PNG、WebP、HEIC、AVIF、RAW、動画、Motion Photo、HDRのJPEG）は、DB上の編集だけを使う。書き戻しの操作は出さず、「この形式は原本に書き込めない」と出す。値を原本に残したいときは、コピーへの書き出し（#45）を使う。
- 書き込んだあとは読み込み直し、書いた項目が一致することと、画像の画素のデータ（最初のSOSから後ろ）のバイトが書き込む前と同じことを確かめる。

## Consequences
多くの写真が該当するJPEGでは、撮影日時と場所と説明を原本に残せる。HEICの写真とタグや評価は原本に書けず、別のアプリやパソコンからは見えない。対応する形式を増やすときは、このADRを改める。

## Rejected alternatives
- PNGとWebPにも書き込む: ExifInterfaceは書けるが、EXIFを読まないアプリが多く、書いても利用者には見えにくい。
- XMPを自前で書き換えてタグと評価を原本に入れる: XMPの解析と再構築を自分で持つことになり、R-05（原本の破損）の危険が増える。
- Motion PhotoとHDRのJPEGにも書き込み、照合で失敗を見つける: 動画やゲインマップの位置がずれても、EXIFと画素の照合では見つからない。
