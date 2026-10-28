# ADR-0013: スクリーンショットテストにRoborazziを使う

- 日付: 2026-10-19
- 状態: Accepted
- 関連: DESIGN §8、ARCHITECTURE §13、#4

## Context
画面の見た目が意図せず変わったことを、PRごとのCIで見つけたい。DESIGN §8では画面のプレビューをスクリーンショットテストにすると決めている。

## Decision
- スクリーンショットテストにRoborazziとRobolectricを使う。
- `@IrohacoPreviews`を付けたプレビューから、テストを自動で作る。プレビューごとに、ライト、ダーク、文字の倍率2.0の画像を撮る。
- 参照画像は各モジュールの`src/test/screenshots/`に置き、CIの`verifyRoborazziDebug`で比べる。差分があればCIを失敗させ、差分の画像をアーティファクトに残す。

## Consequences
エミュレーターなしで、JVMのテストとして画面を比べられる。画面を変えるPRでは参照画像を撮り直してコミットする。RobolectricはAndroidの描画を再現するもので、端末での見た目と完全には一致しない。

## Rejected alternatives
- Google公式のCompose Preview Screenshot Testing: まだalphaで、単体のプラグインは非推奨になり、実験中のAGP test suitesへ移っている途中である。
