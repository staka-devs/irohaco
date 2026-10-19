# Irohaco — データ整合性 Use Case 詳細仕様

- 対応DDL: `../../database/schema.sql`
- 契約案: `../../core/contracts/MediaIntegrityContracts.kt`
- 原則: 書き込みはユースケース経由、DB正本は3NF以上、Android権限は毎回ライブ照会、派生キャッシュと原本を混同しない。

## UC-META-01: EditMetadata

**Precondition**: Assetが存在し、編集対象キーに期待する`local_revision_no`が一致する。EXIF原本へのアクセス許可は不要（DBへの編集）。

1. `metadata_override_sets` の有無とversionを取得。対象タグごとにローカル楽観ロックを使用。
2. `metadata_sync_baselines` がなければ、**現在の原本revisionをその場で再読込**して基準値を固定する。元の基準が復元できない場合は自動上書き禁止／要確認。
3. トランザクション内で`metadata_override_values`を置換し、`edit_mode`をREPLACE/REMOVEへ変更して`local_revision_no += 1`。複数値はoccurrenceで保持。空配列は禁止。
4. 注釈・全文索引・位置検索など影響する派生データのみ更新キューへ登録。画像EmbeddingはEXIF編集だけでは再生成しない。
5. 更新後の値とrevisionを返す。競合時は元値を変更せずUC-META-02へ。

**不変条件**: REMOVEと値ありを両立させない。正本でJSON配列を使用しない。全工程でタグ単位のトランザクションを使用。

## UC-META-02: DetectMetadataConflicts / ResolveMetadataConflict

- Base = `metadata_sync_baselines.base_revision_id`の`metadata_observations`（当該タグ不在も意味を持つ）。
- Local = `metadata_override_sets` + `metadata_override_values`。
- Remote = 直近の原本再読込revisionの`metadata_observations`。
- BaseとRemoteが同値なら原本変更なし。LocalがBaseと同値ならRemoteを優先可能（ただし古いローカル編集が存在しないことをチェック）。
- 両方異なりLocal≠Remoteなら`metadata_conflicts`へ1行登録。ユーザーにBase/Local/Remoteを提示。
- 競合解決は `KEEP_LOCAL` / `ACCEPT_REMOTE` / `MANUAL_MERGE`。マージ後にbase revisionとlocal versionをトランザクションで更新。
- `KEEP_LOCAL` も**原本への自動反映を意味しない**。Export操作は別。

**重要**: 編集のBaseが不明な場合、Baseを推測して3-way mergeしない。まず要確認状態にする。

## UC-META-03: ExportMetadataToOriginal

1. 編集プレビュー。変更箇所、GPSなどのプライバシー影響、ファイル形式別write capabilityを提示。
2. Android `MediaStore.createWriteRequest` / SAF等により必要な書込許可を取得。Uri再チェック。
3. `metadata_write_operations`に操作を記録し、書き込むタグ編集とそのlocal revisionを`metadata_write_operation_items`に記録する。`before_revision_id`と原本ダイジェストを比較し、原本変更があればキャンセル→競合再評価。
4. 書込対応の形式のみステージングコピーを生成。EXIF/XMP/IPTC構造、HDR/Gain Map/Motion Photo/動画コンテナ構造を検証。安全な置換手段がない場合は「コピーへ書き出す」。
5. 原本反映が可能な場合にのみwrite。再読込して編集結果とファイルのデコード可能性を確認し、再読込で得たrevisionを`after_revision_id`に記録して操作状態をCOMMITTEDへ。
6. 失敗・クラッシュ時はRECOVERY_REQUIREDとして再試行またはユーザー提示。記録したlocal revisionより編集が進んでいれば、そのまま再実行せず差分を再評価する。元のタグ・評価をDBから消さない。

## UC-ID-01: ReconcileDiscovery

- MediaStore/SAFの新しい観測はまず`media_discoveries`へ登録。未知の観測を既存の`media_sources.asset_id`へ**直ちに紐付けない**。
- 候補検出は`identity_candidates`、根拠は`identity_candidate_signals`へ保存（内容SHA256、画像ハッシュ、同一URI継続性、EXIF日時など）。
- **バイト単位同一でもコピーが別assetの可能性**がある。SHA256やEXIF日時の一致だけで自動統合禁止。
- 既知のソース継続性がありプロバイダー・volume整合などの確認に成功した場合のみ高信頼の自動リンク可。そうでなければユーザー確認または独立したNEW_ASSETを選択。
- `identity_resolutions`へ判断を記録してから`media_sources`をupsert。ユーザーのタグ・評価・メモが属するAssetIdは変更しない。
- SDカードの一時的取り外しはOFFLINE扱い、未検出はMISSING_CANDIDATE。明示的削除の証拠なしに正本レコードを物理削除しない。

## UC-ACCESS-01: EvaluateMediaAccess / ReconcileAfterResume

1. Android OSの権限を**現在時点**で照会。`READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`、選択的写真アクセス、`ACCESS_MEDIA_LOCATION`は別々に扱う。権限の永続キャッシュを認可に使わない。
2. ContentResolverから該当Uriを開けるか確認。例外は`INACCESSIBLE`で処理し、OFFLINEと区別する。
3. AI検索・RAG・OCR・インデックス生成・外部送信の**各入口・原本読み出し直前**でガード。部分許可の写真以外を検索結果やRAG根拠として返さない。
4. 権限撤回時は表示／検索／外部送信から除外し、`media_access_events`へ監査イベント。派生ベクトル/OCRの保持・削除ポリシーに従い無効化し、既存ユーザーメモは勝手に消さない。
5. 起動時・`onResume`・システム戻り・通信直前の再評価。再許可後はIndexのrevisionと許可範囲を再確認する。
6. `asset_privacy_rules.allow_remote_send`、用途ごとの`egress_grants`、ライブOS許可の**全て**を満たした場合のみ外部送信。どれか欠ければDENY。

> `media_access_events`は「過去に確認した事実」の監査ログであり、認可状態の正本ではない。

## UC-BACKUP-01: ExportEncryptedUserData

- 手動操作のみ開始。対象はAssetIdの論理記録、タグ、評価、アルバム、セマンティックメモ、EXIF編集値、位置のユーザー確定情報、フォルダ設定等。
- デフォルトで画像/動画原本、AIモデル、ベクトル、再生成可能キャッシュ、API秘密情報、外部API許可、OS権限を含めない。
- バージョン付きmanifest+正規化した各record（安定したエクスポート用ID）を逐次書出し。Android Content URI自体を移行先の永続IDとして信用しない。
- 強固なパスフレーズKDF（Argon2id等は依存関係とパラメータを検証）＋AES-256-GCMを基本とする。認証済みmanifest/entry SHA-256を照合。暗号化の独自実装は行わず既存の評価されたライブラリを使用。
- 一時ファイルと完成ファイルを分け、検証後だけ`backup_archives.state=VERIFIED`へ。失敗時に「成功」と表示しない。
- Android Auto Backup／D2D転送ポリシーは別に定義し、OS既定のクラウド転送を黙認しない。

## UC-RESTORE-01: PreviewImport / RestoreUserData

1. 暗号化archiveを開き、KDF・認証タグ・manifest・各エントリのハッシュ・formatVersionを検証（サイズ上限とpath traversal保護）。未知のmajorVersionは拒否。
2. 取り込みは`restore_sessions`のSTAGINGに記録。**正本へ直接上書きしない**。
3. 外部ファイル/MediaStoreを再走査し、保存されたハッシュ・撮影日時・サイズ等で`restore_asset_bindings`の候補を作成。曖昧ならNEEDS_REVIEWとして自動適用しない。
4. ユーザーへ完全一致・曖昧・未発見・衝突件数を提示。未発見は論理プレースホルダーとして残す選択肢を用意。
5. 適用はバッチ/トランザクションで、ユーザーデータを冪等upsert。既存編集は上書きせず衝突解決を提示。
6. `PRAGMA foreign_key_check`、主要レコード数、対応付け、サンプル値を検証しCOMMITTED。失敗時はロールバックまたは復旧手順。
7. **APIキー・egress_grants・OS権限は復元しない。** AIインデックスは再構築可能なキャッシュとして扱う。

## 境界と整合性保証

- `UseCase` 内のDB書込はRoom `withTransaction`を使用。Decode/IO/推論はDBトランザクション外で行い、Commit直前に元revisionとアクセスを再検証。
- Outboxまたは索引ジョブ投入を正本トランザクション内で確定し、途中終了後は冪等に復旧。
- 「一時的に見えない」「許可がない」「元ファイルが削除された」は別の状態として保持。
- DB3NFレビューは新テーブルごとに候補キー・関数従属性を文書化して実施。自動DDLテストだけでは3NFの形式的証明にならない。
