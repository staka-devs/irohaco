# Irohaco 開発ロードマップ（Issue計画）

- 文書ID: IRO-PLAN-001
- バージョン: 1.0.0
- 作成日: 2026-10-19
- 状態: 確定。Issueは随時追加、分割する。完了の状況はGitHubのIssueとMilestoneで管理する
- ゴール: PRD §13のR0リリース判定（M0〜M4の受け入れ条件、AC-001〜AC-012）を通過し、`v1.0.0`を`main`にリリースする
- 進め方: [`CONTRIBUTING.md`](../../CONTRIBUTING.md)に従う

## 1. 進め方

1. Issueは下の表の順に着手する。「依存」の列に書いたIssueが完了してから始める。
2. 各マイルストーンの最後に、基準端末でL3の検証（[`ARCHITECTURE.md`](../../ARCHITECTURE.md) §13.0）を行い、結果をそのマイルストーンの最後のIssueに残す。
3. M1の技術スパイクで動画検索が成り立たなくても、R1へ延期しない。抽出方式、フレームの密度、モデルを変えてR0で成り立たせる（PRD §13）。スパイクの結果に応じてM2以降のIssueを直す。
4. 表のIDは計画上の仮の番号で、GitHubに登録したあとはIssue番号で参照する。

## 2. マイルストーンとIssue

### M0: 基盤（リポジトリ・設計システム・通常ギャラリー）

| ID | タイトル | 種類 / 領域 | 要求・設計 | 依存 |
|---|---|---|---|---|
| M0-01 | Androidプロジェクト雛形とGradle構成（version catalog、`app`・`core:domain`・`core:designsystem`の最小構成、純Kotlinモジュールの依存制約） | chore / app | ARCH §1, CONTRIBUTING §3 | なし |
| M0-02 | CI構築（GitHub Actions: ビルド、Lint、ユニットテスト、参照DDLテスト`tests/`） | chore / ci | ARCH §13.0 L1 | M0-01 |
| M0-03 | `IrohacoTheme`（MaterialExpressiveTheme、Dynamic Color＋静的スキーム、Typography、Shapes、MotionScheme）とプレビュー注釈 | feature / designsystem | DESIGN §1–2 | M0-01 |
| M0-04 | スクリーンショットテスト基盤の選定・導入 | chore / designsystem | DESIGN §8 | M0-03 |
| M0-05 | designsystem共通コンポーネント第1弾（`StatusBadge`、`MediaThumbnail`、`ExpressiveLoading`、`IrohacoIcons`） | feature / designsystem | DESIGN §3, §5 | M0-04 |
| M0-06 | ドメインモデルとPortの移植（`core/contracts/MediaIntegrityContracts.kt`をモジュール化） | feature / domain | ARCH §2, USE_CASES | M0-01 |
| M0-07 | Room DB version 1（`schema.sql`と等価なEntity/DAO、schema export、参照DDLとの差分検証） | feature / database | DATABASE_DESIGN §9, ADR-0005 | M0-06 |
| M0-08 | アプリシェル（`NavigationSuiteScaffold`、4タブ、Edge-to-Edge、Predictive Back） | feature / app | PRD §8.1, DESIGN §3, §6 | M0-03 |
| M0-09 | メディア権限（部分アクセス含む）とMediaStoreカタログ同期（generation差分） | feature / media | PRD GAL-001, ADR-0009, ARCH §6 | M0-07 |
| M0-10 | タイムライン（日付グリッド、ページング、ピンチで列数変更、スクロール位置復元） | feature / viewer | PRD GAL-001, NFR-002 | M0-05, M0-08, M0-09 |
| M0-11 | バックアップ無効化設定（`allowBackup=false`、`dataExtractionRules`） | chore / backup | BACKUP_POLICY, INT-005 | M0-01 |
| M0-12 | エミュレーターCI（Gradle Managed Devices、最小API・最新API） | chore / ci | ARCH §13.0 L2 | M0-08 |
| M0-13 | M0のL3検証（起動とグリッドの性能を初めて計測） | test / app | NFR-001, NFR-002 | M0-10 |

### M1: カタログ・ビューワー・動画プレイヤー・技術スパイク

| ID | タイトル | 種類 / 領域 | 要求・設計 | 依存 |
|---|---|---|---|---|
| M1-01 | 写真ビューワー（全画面、ズーム、パン、スワイプ、共有要素遷移） | feature / viewer | GAL-003 | M0-10 |
| M1-02 | 動画プレイヤー（Media3、再生位置保持、`PlayVideoAt`） | feature / viewer | GAL-004 | M1-01 |
| M1-03 | 詳細情報シートとEXIF/XMP読み取り（欠落・権限不足・解析失敗の区別） | feature / metadata | MET-001 | M1-01 |
| M1-04 | 複数選択とFloatingToolbar（共有・削除→ゴミ箱・Undo） | feature / viewer | GAL-005, DESIGN §3 | M0-10 |
| M1-05 | 移動・コピー・名前変更（権限内） | feature / media | GAL-005 | M1-04 |
| M1-06 | ソート・絞り込み | feature / viewer | GAL-006 | M0-10 |
| M1-07 | ライブラリ（フォルダ階層、内蔵/SDボリューム、除外フォルダ、OFFLINE表示） | feature / media | GAL-002, AC-010 | M0-09 |
| M1-08 | お気に入り・評価・タグ・ユーザーアルバム | feature / metadata | GAL-007 | M0-07 |
| M1-09 | メディア同一性の照合と欠落再確認（`DiscoverNewSources`、`DecideIdentity`、`RecheckMissingAssets`） | feature / media | ADR-0008, INT-003 | M0-09 |
| M1-10 | 安全な表示対応（RAW/HDR/Motion Photoの表示可否の明示） | feature / viewer | GAL-009 | M1-01 |
| M1-11 | Spike A・B・E: 画像と動画の検索（フレーム抽出→Embedding→日本語検索→シーク）の縦貫検証とリソース制限 | spike / ai | AI_SEARCH_SPIKE, ADR-0006 | M1-02 |
| M1-12 | Spike C: 2つのEmbedding索引の共存と切替 | spike / ai | ADR-0003 | M1-11 |
| M1-13 | Spike F: MediaStore永続性（移動・リネーム・SD抜去/復帰） | spike / media | ARCH §16 | M1-09 |
| M1-14 | スパイク結果の判定とM2の計画の見直し | docs / ai | AI_SEARCH_SPIKE | M1-11〜13 |
| M1-15 | フォルダのツリー（展開/折りたたみ、パンくず、リスト/グリッド切替、よく使うフォルダの固定） | feature / media | GAL-011, DESIGN §4 | M1-07 |
| M1-16 | 日付のグループ化（年/月/日）と見出しからの一括選択、ピンチの案内 | feature / viewer | GAL-012 | M1-04, M1-06 |
| M1-17 | ビューワーの撮影日時・場所の既定表示と操作メニュー（地図上で表示） | feature / viewer | GAL-013 | M1-03 |
| M1-18 | グリッドの高速スクロール（スクラバー、日付の表示） | feature / viewer | GAL-001, GAL-014 | M1-16 |
| M1-19 | 共有時に位置情報を外す | feature / metadata | GAL-005, MET-003 | M1-04 |
| M1-20 | ゴミ箱の一覧・復元・完全な削除 | feature / media | GAL-015 | M1-04 |
| M1-21 | アニメーション画像（GIF/アニメーションWebP）の再生 | feature / viewer | GAL-016 | M1-01 |
| M1-22 | GPSの読み取りと地図から探す（外部の地図タイルは許可を得てから） | feature / media | GAL-017, PRD §6, §10 | M1-17 |
| M1-23 | 設定画面のカテゴリ分け | feature / app | APP-001, DESIGN §3 | M0-08 |
| M1-24 | テーマ色の選び方の見直し | feature / designsystem | APP-002, DESIGN §2.1 | M1-23 |
| M1-25 | スクロール中のサムネイルの表示の遅れの解消（MediaStoreの縮小画像、先読み、キャッシュ） | feature / viewer | GAL-001, NFR-002 | M0-10 |
| M1-26 | 動画のサムネイルに再生時間と再生の印を出す | feature / viewer | GAL-001 | M0-10 |
| M1-27 | スクロール中に上部のバーを隠す | feature / viewer | GAL-001, DESIGN §3 | M0-10 |
| M1-28 | 高速スクロールの吹き出しを1行の日付にし、ドラッグ中の読み込みを軽くする | feature / viewer | GAL-014, NFR-002 | M1-18 |
| M1-29 | 地図のまとまりに写真のサムネイルを出す | feature / viewer | GAL-017, ADR-0011 | M1-22 |
| M1-30 | タイムラインのバーを整理し、メニューから地図を開く | feature / viewer | GAL-001, GAL-017, DESIGN §4.1 | M1-22 |
| M1-31 | 固定した日付の見出しが次の見出しと重ならないようにする | feature / viewer | GAL-001, DESIGN §4.1 | M1-16 |
| M1-32 | 地図への同意後にGPSを読み取る権限も求める | bug / viewer | GAL-017, ADR-0011 | M1-22 |

### M2: AI索引・検索・セマンティックメモ・複数索引

| ID | タイトル | 種類 / 領域 | 要求・設計 | 依存 |
|---|---|---|---|---|
| M2-01 | AiResourceManagerとローカル推論Adapter（LiteRT-LM、CPU/GPUプローブ、起動時に自動ロードしない） | feature / ai | MOD-003, MOD-004, ARCH §10 | M1-14 |
| M2-02 | モデル管理（ダウンロード/インポート/ダイジェスト検証/削除）とモデル管理UI | feature / ai | MOD-001, MOD-002 | M2-01 |
| M2-03 | インデックスジョブ基盤（ユーザー起動、チェックポイント、一時停止/再開/キャンセル、FGS制約、熱・電池ポリシー） | feature / indexing | IDX-001, IDX-002, NFR-004, AC-012 | M2-01 |
| M2-04 | インデックス作業画面（SplitButton開始、Wavy進捗、失敗一覧・再試行） | feature / indexing | PRD §8.1-7, DESIGN §4 | M2-03 |
| M2-05 | 画像Embedding索引 | feature / indexing | IDX-003 | M2-03 |
| M2-06 | 動画セグメント・代表フレーム索引（Balanced/Thorough、低カバレッジ表示） | feature / indexing | IDX-004, PRD §5 | M2-05 |
| M2-07 | OCR（バンドル型オフライン）と全文検索（FTS＋日本語N-gram補助） | feature / search | IDX-003, DATABASE_DESIGN §9 | M2-03 |
| M2-08 | ベクトル検索とEmbedding空間の隔離（空間署名、READY切替、構築中の旧索引利用） | feature / search | IDX-006, IDX-007, ADR-0003, AC-004, AC-005 | M2-05 |
| M2-09 | 検索画面（自然言語・類似・条件チップ・根拠表示・写真/動画区間混在・カバレッジ表示） | feature / search | SRCH-001〜004, SRCH-006, AC-002 | M2-06〜08 |
| M2-10 | モデル比較・順位統合（RRF、スコア直接加算禁止） | feature / search | SRCH-005 | M2-08 |
| M2-11 | 検索用メモ（作成・履歴・作成者種別・メモのみ再Embedding） | feature / metadata | ANN-001〜003, AC-007 | M2-07 |
| M2-12 | 選択的再索引（原本変更・メモ変更・モデル変更） | feature / indexing | IDX-005 | M2-11 |
| M2-13 | GPSなし検索と位置証拠（`confirmed_only`/`include_inferred`） | feature / search | PRD §6, SRCH-003, AC-006 | M2-09 |
| M2-14 | M2のL3検証（Golden Setでの検索品質、Spike Dのメモ・GPSなし検索、索引の速度、熱、RAM） | test / ai | NFR-003, NFR-005, NFR-006, NFR-009 | M2-09〜13 |

### M3: RAG・メタデータ編集・原本反映・アクセス隔離

| ID | タイトル | 種類 / 領域 | 要求・設計 | 依存 |
|---|---|---|---|---|
| M3-01 | ADR: EXIF書き戻しの対応形式 | adr / metadata | ARCH §15 未起票 | M2-14 |
| M3-02 | メタデータDB編集（撮影日時・GPS・説明・タグ・評価、出典区別） | feature / metadata | MET-002, MET-005 | M2-14 |
| M3-03 | 外部変更との3-way競合検出と解決UI | feature / metadata | ADR-0007, INT-002 | M3-02 |
| M3-04 | 原本への選択的書き戻し・コピー書き出し・プライバシー情報除去 | feature / metadata | MET-003, AC-008, ADR-0004 | M3-01, M3-03 |
| M3-05 | アクセス権限評価と検索/RAGの隔離（再開時の再評価、派生データの失効） | feature / media | ADR-0009, INT-004 | M2-09 |
| M3-06 | ローカルRAG（Gemma 4、根拠画像/区間の引用、推測と確認済みの区別） | feature / ai | RAG-001〜003 | M3-05 |
| M3-07 | M3のL3検証（RAGの応答時間とメモリ、書き戻しの安全性） | test / ai | NFR-007, NFR-008 | M3-04, M3-06 |

### M4: 外部API・送信許可・バックアップ・品質保証

| ID | タイトル | 種類 / 領域 | 要求・設計 | 依存 |
|---|---|---|---|---|
| M4-01 | ADR: モデル能力ベースのAPIアダプター | adr / api | ARCH §15 未起票 | M3-07 |
| M4-02 | EgressPolicyと用途別許可・送信前確認UI（既定無効、自動フェイルオーバー禁止、Spike Gの検証） | feature / api | API-007, ADR-0002, AC-009 | M4-01 |
| M4-03 | APIキー保護（Keystore）、エラー可視化、ログ秘匿、予算注意 | feature / api | API-008, PRD §10 | M4-02 |
| M4-04 | 生成API Adapter（OpenAI Chat Completions / Responses / Anthropic Messages）と契約テスト | feature / api | API-001〜003, API-006 | M4-03 |
| M4-05 | Embedding API Adapter（OpenAI Embeddings / Gemini embedContent）と契約テスト | feature / api | API-004〜006 | M4-03 |
| M4-06 | 暗号化エクスポートとプレビュー付き復元・再紐付け | feature / backup | ADR-0010, ANN-004, AC-011 | M3-07 |
| M4-07 | ADR: アプリのOSSライセンスとサードパーティライセンス表示 | adr / app | ARCH §15 未起票, PRD §14 | なし |
| M4-08 | 性能ベンチマーク（Macrobenchmark、1万画像＋500動画） | test / app | NFR-001〜003, NFR-010 | M4-05 |
| M4-09 | セキュリティレビューとアクセシビリティ監査 | test / app | PRD §10, DESIGN §7 | M4-06 |

### R0 Release

| ID | タイトル | 種類 / 領域 | 要求・設計 | 依存 |
|---|---|---|---|---|
| R0-01 | 受け入れテストAC-001〜AC-012の実施と記録 | test / app | PRD §12 | M4全体 |
| R0-02 | `release/1.0.0`での安定化、リリースノート、`v1.0.0`タグ | chore / app | CONTRIBUTING §2 | R0-01 |

## 3. 計画に入れないもの

R1とR2の要求（GAL-008、GAL-010、GAL-012のファイル名での区切り、GAL-018〜021、MET-004、API-009、音声検索、ANN、Rustによるネイティブ化）は、R0のIssueに含めない。必要になったときにIssueを作る。
