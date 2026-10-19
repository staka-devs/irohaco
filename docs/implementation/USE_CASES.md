# Irohaco データ整合性 Use Case 詳細仕様

- 対応DDL: `../../database/schema.sql`
- インターフェースの案: `../../core/contracts/MediaIntegrityContracts.kt`
- 原則: 書き込みはUseCaseを通して行う。基本テーブルは3NF以上にする。Androidの権限は毎回その時点の状態を問い合わせる。派生のキャッシュと原本を混同しない。

## UC-META-01: EditMetadata

前提条件: Assetが存在し、編集するキーの`local_revision_no`が期待した値と一致している。DBの中の編集なので、EXIFの原本へのアクセス許可は要らない。

1. `metadata_override_sets` があるかどうかと、その`local_revision_no`を取得する。対象のタグごとにローカルの楽観ロックを使う。
2. `metadata_sync_baselines` がなければ、現在の原本のrevisionをその場で読み込み直して、基準の値を固定する。元の基準の値を取り戻せないときは、自動で上書きせず確認を求める。
3. トランザクションの中で`metadata_override_values`を置き換え、`edit_mode`をREPLACEかREMOVEにして、`local_revision_no`に1を足す。複数の値はoccurrenceで持つ。空の配列は禁止する。
4. 注釈、全文の索引、位置の検索など、影響を受ける派生データだけを更新のキューに登録する。EXIFを編集しただけでは画像のEmbeddingを作り直さない。
5. 更新したあとの値とrevisionを返す。競合したときは元の値を変えず、UC-META-02に進む。

不変条件: REMOVEと値があることを両立させない。基本テーブルでJSONの配列を使わない。どの工程でもタグ単位のトランザクションを使う。

## UC-META-02: DiscoverMetadataConflicts / ResolveMetadataConflict

- Baseは`metadata_sync_baselines.base_revision_id`の`metadata_observations`とする（そのタグがないことにも意味がある）。
- Localは`metadata_override_sets`と`metadata_override_values`とする。
- Remoteは最後に原本を読み込み直したrevisionの`metadata_observations`とする。
- BaseとRemoteが同じ値なら、原本は変わっていない。LocalがBaseと同じ値ならRemoteを優先してよい（ただし、古いローカルの編集が残っていないことを確かめる）。
- 両方が異なり、LocalとRemoteも違うときは、`metadata_conflicts`に1行登録し、ユーザーにBase、Local、Remoteを示す。
- 競合の解決は`KEEP_LOCAL`、`ACCEPT_REMOTE`、`MANUAL_MERGE`のどれかとする。マージしたら、base revisionとlocal revisionをトランザクションで更新する。
- `KEEP_LOCAL`を選んでも、原本に自動で反映するわけではない。エクスポートは別の操作である。

編集のBaseが分からないときは、Baseを推測して3-way mergeをしない。まず確認が必要な状態にする。

## UC-META-03: ExportMetadataToOriginal

1. 編集のプレビューを表示する。変わる箇所、GPSなどのプライバシーへの影響、ファイル形式ごとに書き込めるかどうかを示す。
2. Androidの`MediaStore.createWriteRequest`やSAFなどで、必要な書き込みの許可を得て、Uriを確かめ直す。
3. `metadata_write_operations`に操作を記録し、書き込むタグの編集とそのlocal revisionを`metadata_write_operation_items`に記録する。`before_revision_id`と原本のダイジェストを比べ、原本が変わっていたらキャンセルして競合を評価し直す。
4. 書き込みに対応した形式でだけ、作業用のコピーを作る。EXIF、XMP、IPTCの構造と、HDR、Gain Map、Motion Photo、動画のコンテナの構造を検証する。安全に置き換える手段がないときは「コピーへ書き出す」にする。
5. 原本に反映できるときだけ書き込む。読み込み直して、編集の結果とファイルをデコードできることを確かめ、読み込み直して得たrevisionを`after_revision_id`に記録し、操作の状態をCOMMITTEDにする。
6. 失敗したときとクラッシュしたときはRECOVERY_REQUIREDにし、再試行するかユーザーに示す。記録したlocal revisionより編集が進んでいたら、そのまま再実行せず差分を評価し直す。元のタグと評価はDBから消さない。

## UC-ID-01: DiscoverNewSources / DecideIdentity

- MediaStoreやSAFで新しく見つけたものは、まず`media_discoveries`に登録する。知らない観測を、既存の`media_sources.asset_id`にすぐには結び付けない。
- 候補は`identity_candidates`に、根拠は`identity_candidate_signals`に保存する（内容のSHA256、画像のハッシュ、同じURIが続いていること、EXIFの日時など）。
- バイト単位で同じでも、コピーは別のassetであることがある。SHA256やEXIFの日時が一致しただけで自動統合することは禁止する。
- 既知のソースが続いていて、プロバイダーやボリュームの整合を確かめられたときだけ、信頼度の高い自動のリンクを認める。そうでなければ、ユーザーに確認するか、独立したNEW_ASSETにする。
- `identity_resolutions`に判断を記録してから、`media_sources`をupsertする。ユーザーのタグ、評価、メモが属するAssetIdは変えない。
- SDカードを一時的に取り外したときはOFFLINE、見つからないときはMISSING_CANDIDATEとして扱う。明示的に削除した証拠がなければ、基本テーブルのレコードを物理的に削除しない。

## UC-ACCESS-01: EvaluateMediaAccess / ReconcileAfterResume

1. Android OSの権限を、その時点で問い合わせる。`READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`、写真の選択的なアクセス、`ACCESS_MEDIA_LOCATION`は別々に扱う。権限を永続的にキャッシュして、認可に使うことはしない。
2. ContentResolverで該当するUriを開けるかを確かめる。例外が出たら`INACCESSIBLE`として扱い、OFFLINEと区別する。
3. AI検索、RAG、OCR、インデックスの作成、外部への送信のそれぞれの入口と、原本を読み出す直前で確認する。部分的に許可された写真以外を、検索結果やRAGの根拠として返さない。
4. 権限が取り消されたら、表示、検索、外部への送信から除き、`media_access_events`に監査のイベントを残す。派生のベクトルとOCRは、保持と削除の方針に従って無効にし、既存のユーザーのメモは勝手に消さない。
5. 起動時、`onResume`、システムの画面から戻ったとき、通信の直前に評価し直す。許可し直されたら、インデックスのrevisionと許可の範囲を確かめ直す。
6. 外部へ送るのは、`asset_privacy_rules.allow_remote_send`、用途ごとの`egress_grants`、その時点のOSの許可をすべて満たしたときだけとする。1つでも欠けたら拒否する。

> `media_access_events`は「過去に確かめた事実」の監査ログで、認可の状態を決めるものではない。

## UC-BACKUP-01: ExportEncryptedUserData

- ユーザーの操作でだけ始める。対象は、AssetIdの論理的な記録、タグ、評価、アルバム、セマンティックメモ、EXIFの編集値、ユーザーが確定した位置の情報、フォルダの設定などとする。
- 既定では、画像と動画の原本、AIモデル、ベクトル、作り直せるキャッシュ、APIの秘密情報、外部APIの許可、OSの権限は含めない。
- バージョン付きのmanifestと、正規化した各レコード（エクスポート用の安定したID付き）を順に書き出す。AndroidのContent URIを、移行先での永続的なIDとして信用しない。
- パスフレーズからの鍵の導出には強いKDFを使い（Argon2idなどは依存関係とパラメーターを検証する）、AES-256-GCMを基本とする。認証済みのmanifestとエントリのSHA-256を照合する。暗号は自前で実装せず、評価された既存のライブラリを使う。
- 作業中のファイルと完成したファイルを分け、検証が済んでから`backup_archives.state=VERIFIED`にする。失敗したときに「成功」と表示しない。
- AndroidのAuto Backupと端末間の転送の方針は別に決め、OSの既定のクラウド転送を黙って受け入れない。

## UC-RESTORE-01: PreviewEncryptedImport / ApplyRestore / VerifyRestore

1. 暗号化されたアーカイブを開き、KDF、認証タグ、manifest、各エントリのハッシュ、formatVersionを検証する（サイズの上限とpath traversalへの対策も行う）。知らないmajorVersionは拒否する。
2. 取り込みは`restore_sessions`のSTAGINGとして記録し、基本テーブルを直接上書きしない。
3. 外部のファイルとMediaStoreを走査し直し、保存したハッシュ、撮影日時、サイズなどで`restore_asset_bindings`の候補を作る。あいまいなものはNEEDS_REVIEWにし、自動では適用しない。
4. 完全に一致した件数、あいまいな件数、見つからなかった件数、衝突した件数をユーザーに示す。見つからなかったものは、論理的なプレースホルダーとして残す選択肢を用意する。
5. 適用はバッチとトランザクションで行い、ユーザーデータを冪等にupsertする。既存の編集は上書きせず、衝突の解決を示す。
6. `PRAGMA foreign_key_check`、主なレコードの件数、対応付け、サンプルの値を検証してからCOMMITTEDにする。失敗したらロールバックするか、復旧の手順に進む。
7. APIキー、egress_grants、OSの権限は復元しない。AIのインデックスは作り直せるキャッシュとして扱う。

## 境界と整合性の保証

- `UseCase`の中のDBの書き込みにはRoomの`withTransaction`を使う。デコード、IO、推論はDBトランザクションの外で行い、コミットの直前に元のrevisionとアクセスを確かめ直す。
- Outboxや索引のジョブの登録は基本テーブルのトランザクションの中で確定し、途中で終了しても冪等に復旧できるようにする。
- 「一時的に見えない」「許可がない」「元のファイルが削除された」は別の状態として持つ。
- DBの3NFのレビューでは、新しいテーブルごとに候補キーと関数従属性を文書に書く。DDLの自動テストだけでは、3NFを形式的に証明したことにならない。
