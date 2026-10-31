# Irohaco UI Design System Specification (Material 3 Expressive)

- 文書ID: IRO-DESIGN-001
- バージョン: 1.0.0
- 作成日: 2026-10-19
- 状態: 確定
- 要求仕様: [`PRD.md`](./PRD.md) §2.1 G-06、§8
- アーキテクチャ: [`ARCHITECTURE.md`](./ARCHITECTURE.md) §11（UIアーキテクチャ）
- 実装先: `core/designsystem/`（トークン、テーマ、共通コンポーネント、モーション）

この文書はIrohacoのUIをMaterial 3 Expressive（以下M3E）に準拠させるための実装規約である。画面とコンポーネントの実装、レビュー、Issueの完了条件はこの文書に従う。M3E公式ガイドラインと食い違う場合は公式に合わせ、この文書を直す。

## 0. デザイン原則

1. 写真と動画を主役にする。表示面にはM3Eの装飾（シェイプモーフ、強調色、大きなコンテナ）を載せず、Expressiveな表現は操作、状態、画面遷移に使う。
2. 1画面の主操作は1つに絞り、サイズと色とシェイプで目立たせる（例: 検索、インデックス開始、選択中の一括操作）。
3. AI未導入、未索引、索引作成中、期限切れ、OFFLINE、権限不足は区別して表示する（PRD §8.2）。進捗と待ち状態は必ず画面に出す。
4. AI関連のUIは段階的に見せる。AIが失敗してもギャラリーの操作は止めない。
5. Compose Material 3の公式コンポーネントを使う。公式にないものだけを`core/designsystem`内で作る（ARCHITECTURE R-10）。

## 1. 技術基盤

| 項目 | 規定 |
|---|---|
| UIライブラリ | Jetpack Compose と`androidx.compose.material3`（M3E APIを含む1.5系）。バージョンはversion catalogで固定し、ベータ版から安定版への切り替えはIssueで扱う |
| アダプティブ | `material3-adaptive`、`material3-adaptive-navigation-suite`（`NavigationSuiteScaffold`）、`ListDetailPaneScaffold` |
| テーマ | `MaterialExpressiveTheme`を`IrohacoTheme`で包む。アプリでは`IrohacoTheme`以外のテーマを直接使わない |
| 実験的API | `@ExperimentalMaterial3ExpressiveApi`などのopt-inは`core/designsystem`の中だけで使う。feature層は安定APIか、designsystemのラッパーだけを使う。APIが安定したらラッパーを薄くする |
| アイコン | Material Symbols（Rounded。可変フォント軸はFILL 0/1、wght 400、GRAD 0、opsz 24）。選択状態はFILL=1にする |
| 画像の読み込み | Coil系。サムネイルのプレースホルダー色は`surfaceContainerHigh` |

### 1.1 2026-10-19時点のAPIの状況（Material3 1.5.0-beta01）

- 安定API: Expressiveボタン、`ButtonGroup`、`SplitButton`、`ToggleButton`、`FloatingToolbar`、FABと`FloatingActionButtonMenu`、Expressiveメニュー、Expressiveリスト項目、Expressive `TopAppBar`
- 実験的API（opt-inが必要）: `LoadingIndicator`、`MaterialShapes`。そのほかのAPIは各APIリファレンスのアノテーションで確かめる
- ライブラリを更新したらこの一覧を直し、opt-inが要らなくなったAPIのラッパーを見直す

## 2. デザイントークン

feature層では色の値（`Color(0x…)`）、dpとspの数値、`tween`の持続時間を直接書かない。すべて`MaterialTheme`か`IrohacoTokens`から参照する。

### 2.1 Color

- 既定は端末の壁紙から作られるDynamic Color（Material You。`dynamicLightColorScheme` / `dynamicDarkColorScheme`）にする。
- 設定の「テーマの色」の「端末の色を使用」をオフにすると、3つのタブで色を選べる。「基本」は10の色相の500を丸い見本で、「パレット」はMaterial UIのカラーパレット（19の色相と、それぞれの14段。`MaterialPalette`）を、「カラーコード」は`#RRGGBB`での指定を扱う。選んだ色をシードにし、MaterialKolorでLightとDarkのスキームを作る。「端末の色を使用」がオンのとき、タブは選べない。オフにした直後はBlue 500を使う。
- 設定の「コントラスト」で、「端末の設定」（既定。Android 14以降のシステムの設定）、「標準」、「中」、「高」を選べる。端末の壁紙の色を使うときに標準以外を選んだ場合は、壁紙から作られた主の色をシードにしてスキームを作り直す。
- Dynamic Colorを使えないとき、プレビュー、スクリーンショットテストでは、Blue 500（#2196F3）をシードにしたスキームを使う。
- 色は役割で選ぶ。主操作は`primary`、補助の操作は`secondary`か`tertiary`、面は`surface*`、エラーは`error`を使う。面の階層は`surfaceContainerLowest`から`Highest`までの5段で表し、影より面の色で区別する。
- ビューワーと動画プレイヤーは、テーマに関係なく黒背景にし、前景は白系にする。重ねるUIは`scrim`系の半透明の背景に載せる。
- 状態は色だけで伝えない（§5）。

### 2.2 Typography

- M3のタイプスケール15スタイルと、Emphasizedスタイルを使う。Emphasizedは見出し、主操作、選択状態のような強調する箇所だけに使う。
- フォントはシステム既定を使う（日本語はシステムのCJKフォント）。独自のフォントを同梱するときは、ライセンスを確かめてADRを起こす。
- 用途ごとのスタイルは次のとおりとする。

| 用途 | スタイル |
|---|---|
| 画面タイトル（大きいTopAppBar） | `displaySmall`か`headlineLarge`（Emphasizedも可） |
| タイムラインの日付の見出し | `titleMedium`（Emphasized） |
| リスト項目の主テキスト | `bodyLarge` |
| メタデータの値と補足 | `bodyMedium`か`bodySmall` |
| チップとボタン | `labelLarge` |
| 動画のタイムコードバッジ | `labelMedium`（数字は等幅にする。`fontFeatureSettings = "tnum"`） |

- 文字の倍率を200%にしても、文字が切れたり重なったりしないようにする（§7）。

### 2.3 Shape

- M3Eのシェイプスケール（None、Extra small、Small、Medium、Large、Large increased、Extra large、Extra large increased、Extra extra large、Full）を`IrohacoTheme`の`Shapes`に定義する。
- 要素ごとのシェイプは次のとおりとする。

| 要素 | シェイプ |
|---|---|
| グリッドのサムネイル | Extra small（選択するとMediumへ変形し、少し縮める） |
| アルバムや検索結果のカード | MediumからLarge |
| ボトムシートとダイアログ | Extra large |
| FABと主ボタン | M3Eの既定（押すとシェイプが変わる） |
| 空状態のイラストやAI準備完了のバッジなどのアクセント | `MaterialShapes`（designsystemのラッパーから使う） |

- `MaterialShapes`によるシェイプの変形は状態の切り替え（読み込みから完了、未選択から選択）と空状態だけに使う。写真のサムネイルを装飾のシェイプで切り抜かない。

### 2.4 Motion

- `MotionScheme.expressive()`を既定にして`IrohacoTheme`から渡す。アニメーションには`MaterialTheme.motionScheme`のスプリングを使う。
  - 位置、サイズ、シェイプの変化には`defaultSpatialSpec`、`fastSpatialSpec`、`slowSpatialSpec`を使う。
  - 色と不透明度の変化には`defaultEffectsSpec`、`fastEffectsSpec`、`slowEffectsSpec`を使い、バウンスさせない。
- グリッドからビューワーへの遷移は共有要素遷移（Shared Element）にする。戻る操作はPredictive Backのプレビューに合わせて動かす。
- システムの「アニメーションを削除」（animator duration scale = 0）が有効なときは、変形とバウンスをやめてすぐに切り替える。判定は`core/designsystem`の`LocalReducedMotion`にまとめる。
- スクロール中のグリッドでは重いアニメーションを動かさない（NFR-002）。

### 2.5 Spacing と Elevation

- 4dpグリッドを使う。`IrohacoTokens.spacing`（`xxs=2, xs=4, s=8, m=12, l=16, xl=24, xxl=32`）を定義して参照する。
- 画面の左右の余白はCompactで16dp、Mediumで24dp、Expandedで24dpとし、Expandedではペインの間も24dp空ける。
- 階層は面の色で表し、影はFABや浮いたツールバーのような浮いている要素だけに付ける。

## 3. 用途とコンポーネントの対応

| 用途 | コンポーネント | 備考 |
|---|---|---|
| 主なナビゲーション（写真、アルバム、検索、ライブラリ） | `NavigationSuiteScaffold`（Compactは`ShortNavigationBar`、Medium以上は`WideNavigationRail`） | タブは4つ。設定はTopAppBarのメニューから開く |
| 画面のヘッダー | Expressive `TopAppBar`（Small、Medium flexible、Large flexible） | スクロールすると折りたたむ |
| 検索の入口 | `SearchBar`か`AppBarWithSearch`。展開したら`ExpandedFullScreenSearchBar` | 写真タブの上部から開く |
| 複数選択中の一括操作 | `HorizontalFloatingToolbar`（Medium以上は`VerticalFloatingToolbar`も可） | 共有、移動、削除、タグ、アルバムに追加 |
| 絞り込みの条件 | `FilterChip`、`InputChip` | 意味、日時、位置、文字、メモ（SRCH-004） |
| 表示の切り替え（グリッドの密度、写真と動画） | `ButtonGroup`と`ToggleButton` | 1つだけ選ぶものはconnected形式にする |
| Embeddingモデルの切り替え | 2〜3件なら`ButtonGroup`、それより多ければ`ExposedDropdownMenu` | READYでないモデルは状態ラベルを付け、選べなくするか警告を出す（AC-005） |
| インデックスの開始 | `SplitButton`（主ボタンで開始、副ボタンで対象と品質を選ぶ） | 確認ダイアログは初回だけ出す |
| 作成の主操作 | FAB、`FloatingActionButtonMenu` | アルバムの作成やメモの追加など。1画面に1つ |
| 短い待ち時間（1秒から数秒） | `LoadingIndicator`、`ContainedLoadingIndicator` | 全画面の`CircularProgressIndicator`は使わない |
| 長い処理の進捗（索引作成、ダウンロード） | `LinearWavyProgressIndicator`（進捗率を表示する） | 件数、残り、失敗数も並べて出す |
| アルバムの一覧 | `HorizontalMultiBrowseCarousel`とグリッド | |
| 詳細情報とメタデータ | Compactでは`ModalBottomSheet`、Expandedではサイドのペイン | |
| 外部送信の確認と、元に戻せない操作 | `AlertDialog` | 送信先、送信する内容、件数を示す（API-007） |
| 通知と取り消し | `Snackbar`（元に戻す操作を付ける） | 削除はゴミ箱への移動にし、元に戻せるようにする |
| 設定の項目 | Expressiveリスト項目と`Switch` | |
| 動画の区間 | 代表フレームとタイムコードバッジ（`Badge`をラップする） | タップすると`PlayVideoAt`を呼ぶ |

## 4. 画面ごとのガイド（PRD §8.1）

1. 写真（タイムライン）: Large flexibleのTopAppBarと検索バーを置く。日付の見出しはスクロールしても上に残す。ピンチでグリッドの列数を段階的に変える（Compactは3〜28列、Mediumは5〜40列、Expandedは6〜45列）。列が増えるほど1段階の差を大きくし、数回のピンチで全体を見渡せるようにする。サムネイルは列の数に合った大きさで読み込む。上部のバーには設定と並べ替えを置き、「その他」（⋮）のメニューに「地図で表示」と列の数の増減を置く。列の数の増減は、ピンチを使えない人のために残す。長押ししてからドラッグすると範囲を選べ、選択中は`FloatingToolbar`を出す。
2. アルバム: お気に入り、ユーザーアルバム、スマートアルバムをカルーセルとグリッドで並べる。作成はFABから行う。
3. 検索: 検索バー、条件のチップ、モデルの切り替え、結果のグリッド（写真と動画の区間を混ぜて表示）を置く。一致した根拠はカードの下部にチップで示す。索引のカバレッジが足りないときはバナーで知らせる（SRCH-006）。RAGの回答は結果の上に引用付きのカードで出し、引用をタップすると該当するメディアや区間へ移る。
   - 検索の文に日付（去年の夏、先月、8月など）や、写真か動画かが入っているときは、決まった規則で読み取り、日付と種類のチップに当てる。残りの語で意味と文字を探す。文が場所の名前を含むときは、その場所のチップを出し、オンの間はその場所の写真と動画だけを出す。どのチップも外せる。
   - 検索バーのAIのボタンで、AIに自由に質問する画面を開く。質問ごとに日付、種類、場所を読み取って探し、上位の写真と場面から答える。続けて質問すると、直前の答えが引いた写真と場面を2つまで残し、新しく探した結果と合わせてモデルに渡す。どれを使うかはモデルが選ぶ。
   - 回答のカードは、結果があるときに出す「この結果について AI に聞く」で作る。カードには回答の文、根拠の帯、AIが間違うことがある旨の注記を順に置く。
   - 回答の文は根拠を`[1]`のような番号で引く。根拠の帯には、回答が引いた根拠だけを横に並べる。根拠は多くても4件にする。モデルに渡す写真と動画の場面が4件までなので、帯もそれを超えない。
   - 根拠の帯の各サムネイルの下に、番号と根拠の種類を出す。動画の場面はサムネイルに時刻を出し、タップするとその時刻から再生する。
   - 根拠の種類は「写っている」「メモにある」「推測」の3つにする。「推測」はtertiaryの色の斜体にして、記録に基づくものと見分けられるようにする。色だけに頼らないように、種類は必ず文字で出す。TalkBackでは「根拠1、写真、写っている」のように読む。
   - 根拠で答えられないときは、モデルに分からないと答えさせる。類似度のしきい値で回答を止めることはしない。類似度は空間ごとに意味が違い、RRFで順位を統合したあとの結果には共通の目安がないためである。しきい値が要るかは、M3のL3検証（#48）の結果で決め直す。
   - 回答の文は、モデルが書いた順にカードへ出す。モデルが書き始めるまでは、作っている旨とインジケーターを出す。
   - モデルが入っていないときは、カードに設定を開くボタンを出す。答えを作れなかったときは、もう一度のボタンを出す。
   - 根拠を外して答え直す操作は作らない。
4. ライブラリ: フォルダのツリー、内蔵とSDのボリューム、タグを並べる。OFFLINEのボリュームはグレーアウトせず、状態のラベルとアイコンで示す。
5. 設定 > AI: モデルの一覧（状態バッジ付き）、Embedding空間、インデックスの開始（`SplitButton`）、外部API（既定はOFFのSwitch。有効にするときは用途ごとに確認する）を置く。
6. 詳細（ビューワー、動画プレイヤー）: 黒背景の全画面にする。タップすると重ねたUIを出し入れする。情報はボトムシートに出し、メタデータとメモの編集は別の画面か全画面のシートで行う。
   - 「その他」のメニューの「AI に聞く」で、開いている写真か、再生中の動画の場面について質問するボトムシートを開く。問いと答えを上から順に並べ、答えには検索の回答と同じカードを使う。続けて質問すると、直前の3つまでの問いと答えをモデルに渡す。別のページに移るか、シートを閉じると、問答を消す。
7. インデックス作業: 進捗率をWavyのインジケーターで出し、写真の件数、動画のセグメント数、失敗した件数、使う容量の見込みを並べる。一時停止、再開、キャンセルは`ButtonGroup`に置き、失敗の一覧から再試行できるようにする。
8. 位置情報の履歴（設定 > プライバシー、ライブラリの「場所のない写真」、タイムラインの複数選択の「場所を付ける」から開く）: Googleマップから書き出した`Timeline.json`を選んで取り込み、GPSのない写真の場所の候補を、場所ごとに1行ずつ並べる。日付をまたいだ同じ場所の写真は1行にまとめ、「日ごとに分ける」を選ぶと日付と場所ごとに分ける。「日付」で撮った日の範囲を選ぶと、その間に撮った写真の候補だけを出す。1行には最初の写真、場所の名前（ないときは座標）、件数、撮った日（またぐときはその範囲）、却下と確定のボタンを出す。行をタップすると、その下に確かさ（滞在していた場所、移動中の場所、時刻が少し離れた移動中の場所）とサムネイルの列を開く。オンライン地図を許可しているときは、場所に印を付けた地図の画像も出す（ADR-0011）。「候補のない写真」のタブには、GPSも利用者が付けた場所も候補もない写真をサムネイルで並べる。写真を選んで「場所を付ける」を押すと、場所の付いた写真にある場所の名前と、候補の場所の名前から選べる。選んだ場所は確定と同じくDBの上で付ける。複数選択から開いたときは、選んだ写真の候補だけを出す。ビューワーの詳細では候補を「場所（履歴）」として出し、1枚ずつ確定と却下ができる。開いた行でサムネイルをタップして選ぶと、その行の「確定」と「却下」は選んだ写真だけに効く。選んでいないときは、その行の全部に効く。確定するとDBの上で場所を付け、写真のファイルはビューワーから書き込むまで変えない（ADR-0029）。読めないファイルは理由を出して取り込まない。

## 5. 状態の表示

状態はアイコン、テキスト、色の3つで示し、色だけに頼らない。表示は`core/designsystem`の`StatusBadge`でそろえる。

| 状態 | 元になる値 | 色の役割 | アイコン（Material Symbols） | 表示の例 |
|---|---|---|---|---|
| 検索できる | `lifecycle_state = READY` | `primaryContainer` | `check_circle` | 検索できます |
| 索引作成中 | `lifecycle_state = BUILDING`、`VERIFYING` | `secondaryContainer` | `progress_activity`と進捗率 | 索引作成中 42% |
| 未索引 | `lifecycle_state = PLANNED` | `surfaceContainerHighest` | `radio_button_unchecked` | 未索引 |
| 更新が必要 | `lifecycle_state = STALE` | `tertiaryContainer` | `update` | 更新が必要 |
| 失敗 | `lifecycle_state = FAILED` | `errorContainer` | `error` | 失敗（理由を表示） |
| 削除中 | `lifecycle_state = DELETING` | `surfaceContainerHighest` | `delete` | 削除中 |
| SDカード未接続 | `access_state = OFFLINE` | `surfaceContainerHighest` | `sd_card_alert` | SDカード未接続 |
| 権限なし | `access_state = INACCESSIBLE` | `errorContainer` | `lock` | アクセス権限がありません |
| 低カバレッジ | 索引の対象範囲から算出 | `tertiaryContainer` | `data_alert` | 低カバレッジ |
| AI未導入 | モデルのプロファイルがない | `surfaceContainerHigh` | `smart_toy` | AIモデル未導入（設定を開く） |

- `lifecycle_state`は`embedding_spaces.lifecycle_state`、`access_state`は`media_sources.access_state`を指す。状態の名前と遷移は[`database/INDEX_STATE_MACHINE.md`](./database/INDEX_STATE_MACHINE.md)と[`database/schema.sql`](./database/schema.sql)に合わせる。UIの文言はstringsリソースに置く。

## 6. アダプティブレイアウトとシステムとの連携

- Window Size Class（Compact、Medium、Expanded、Large、Extra-large）でレイアウトを切り替える。横画面、折りたたみ端末、タブレットでは`ListDetailPaneScaffold`を使う（例: ライブラリとフォルダの中身、検索結果と詳細）。
- Edge-to-Edgeにする。インセットは`WindowInsets`で扱い、グリッドはシステムバーの下までスクロールさせる。
- Predictive Backに対応する。シート、検索、ビューワー、選択モードの解除が対象になる。
- 画面の回転とプロセスの再生成のあとも、スクロール位置、選択状態、ビューワーの位置を戻す。

## 7. アクセシビリティ

- タッチターゲットは48×48dp以上にする。サムネイルは見た目が小さくても、この大きさのターゲットを確保する。
- TalkBackでは、サムネイルについて種類、撮影日時、動画なら長さ、選択状態を読み上げるよう`contentDescription`を付ける。ピンチやドラッグ選択のような独自のジェスチャーには、同じことができるメニュー操作も用意する。
- 文字の倍率を200%にし、表示サイズを最大にしてもレイアウトが崩れないようにする。
- コントラスト比は本文で4.5:1以上、大きい文字とUI部品で3:1以上にする。High contrastのスキームで確かめる。
- アニメーションを減らす設定には§2.4のとおり従う。

## 8. `core/designsystem`の構成と実装の決まり

```text
core/designsystem/
├── theme/        # IrohacoTheme, ColorScheme生成, Typography, Shapes, MotionScheme
├── tokens/       # IrohacoTokens（spacing, sizes, durations以外の定数）
├── component/    # StatusBadge, TimecodeBadge, MediaThumbnail, SelectionToolbar, ExpressiveLoading 等
├── icon/         # IrohacoIcons（Material Symbolsの参照を集約）
├── motion/       # LocalReducedMotion, 共有要素遷移ヘルパー
└── preview/      # @IrohacoPreviews（Light/Dark/Dynamic off/文字倍率2.0/Compact・Expanded）
```

- 共通コンポーネントには`@IrohacoPreviews`のプレビューを付ける。
- designsystemの全コンポーネントと主な画面には、Light、Dark、文字の倍率2.0のスクリーンショットテストを用意し、CIで比べる。ツールはRoborazziで、`@IrohacoPreviews`を付けたプレビューから自動でテストを作る。参照画像は`src/test/screenshots/`に置き、見た目を意図して変えたときは`./gradlew recordRoborazziDebug`で撮り直してコミットする。
- feature層で`Color(0x`、`MaterialTheme`以外のテーマ、opt-inの注釈を使っていないかを、独自のLintルールかDetektのルールで調べる。
- 文字列はすべてリソースに置き、日本語と英語を用意する。

## 9. 禁止事項

- Google純正アプリの固有の画面、ブランド要素、アイコン、イラストを複製すること（ARCHITECTURE R-10）。
- 写真と動画の表示面に、装飾のシェイプ、グラデーション、強調色を重ねること。
- feature層で色の値、寸法、アニメーションの時間を直接書くこと、実験的APIを直接使うこと。
- 状態を色だけで伝えること。
- AIの処理中に、全画面のローディングでギャラリーの操作を止めること。

## 10. UIレビューのチェックリスト（PRの完了条件）

- [ ] `IrohacoTheme`とトークンだけを使い、値を直接書いていない
- [ ] 実験的APIを`core/designsystem`の中だけで使っている
- [ ] Light、Dark、Dynamic Color無効、High contrastで表示を確かめた
- [ ] 文字の倍率200%とTalkBackで操作できる
- [ ] CompactとExpandedのレイアウトを確かめた（対象の画面だけ）
- [ ] Edge-to-EdgeとPredictive Backに対応している
- [ ] 状態の表示が§5に従っている
- [ ] アニメーションを減らす設定のとき、すぐに切り替わる
- [ ] スクリーンショットテストを追加または更新した

## 11. 参考資料（2026-10-19確認）

- Material 3 Expressive: https://m3.material.io/blog/building-with-m3-expressive
- Material Design 3: https://m3.material.io/
- Material 3 in Compose: https://developer.android.com/develop/ui/compose/designsystems/material3
- Compose Material 3 リリースノート: https://developer.android.com/jetpack/androidx/releases/compose-material3
- Material Symbols: https://fonts.google.com/icons
- Adaptive layouts in Compose: https://developer.android.com/develop/ui/compose/layouts/adaptive
- Predictive back: https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture
