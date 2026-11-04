# ADR-0033: アプリは Apache License 2.0 で公開し、ライブラリのライセンスはビルドのときに集めてアプリで見せる

- 日付: 2026-11-04
- 状態: Accepted
- 関連: PRD §14、ARCHITECTURE §12、§15、#55

## Context
PRD は Irohaco を OSS にする予定としていて、アプリのライセンスは決めていなかった。アプリが同梱するライブラリには、Apache License 2.0 のもの（AndroidX、Kotlin、Coil、Media3 など）と、それ以外のもの（Bouncy Castle の MIT 型のライセンスなど）がある。配布するときは、それぞれのライセンスの表示が要る。

## Decision
- アプリのコードは Apache License 2.0 で公開する。全文はリポジトリの直下の `LICENSE` に置く。特許の許諾と、その終了の条項があることを理由に選ぶ。依存するライブラリの多くと同じライセンスである。
- 同梱するライブラリのライセンスは、AboutLibraries の Gradle プラグイン（`com.mikepenz.aboutlibraries.plugin.android`）でビルドのときに集め、`res/raw/aboutlibraries` に入れる。
  - ライブラリとライセンスの対応は、依存の POM から取る。ライセンスの本文はビルドのときに SPDX から取得する。アプリが実行中に取得することはない。
  - SPDX にないライセンスのうち、本文を同梱するよう求めるもの（Bouncy Castle）は、`app/aboutlibraries/licenses/` に本文を置いて補う。利用規約の形のもの（ML Kit、Android SDK）は、名前と URL を見せる。
  - AGP 9 の資源の縮小で消えないように、keep の指定を置く。
- アプリは「設定 > このアプリについて」で、アプリのライセンスと、ライブラリごとの名前、版、ライセンスを見せる。ライブラリを選ぶと、ライセンスの全文を見せる。
- 利用者がダウンロードする AI モデル（EmbeddingGemma など）は同梱しないので、この一覧には入れない。モデルの利用条件はモデル管理の画面とダウンロードの元で扱う。

## Consequences
ライブラリを足したりバージョンを上げたりしたときも、一覧はビルドから作り直されるので、手で直す必要はない。ただし、POM にライセンスを書いていないライブラリや、SPDX にないライセンスを足したときは、AboutLibraries の設定ファイルで補う。ビルドが SPDX の本文を取得するので、ビルドには通信が要る。

## Rejected alternatives
- Google の oss-licenses プラグイン: 表示に Google Play 開発者サービスのライブラリが要る。Irohaco は Play 開発者サービスに頼らない。
- 手で書いた一覧: 依存が変わるたびに直す必要があり、漏れやすい。
- MIT License: 短く制限が少ないが、特許の許諾の条項がない。
