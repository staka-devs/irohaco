# Android OS バックアップ方針（実装ガイド）

Irohacoの既定は完全ローカル。OSの自動バックアップと、アプリ自身の**ユーザー操作による暗号化エクスポート**は別物。

## 推奨

- データ保護を最優先し、初期リリースではOSのクラウドバックアップをオプトアウトする。
- 端末間転送も自動のままなら秘密情報や重いキャッシュを転送する可能性がある。初期実装では自動移行を無効として手動暗号化インポートを用意する。
- `AndroidManifest`の `android:allowBackup="false"` を指定し、同時に Android 12+ の`android:dataExtractionRules`と旧版の`android:fullBackupContent`の制御を具体化する。
- Android OS / OEM により `allowBackup=false` のみではD2D転送を必ず止められるとは限らない。ルールを実機で検証する。
- 以下は**実装雛形**（アプリのビルドに組み込む前にターゲットAPIと検証対象OSで要確認）。

```xml
<!-- AndroidManifest.xml <application> attributes -->
android:allowBackup="false"
android:dataExtractionRules="@xml/data_extraction_rules"
android:fullBackupContent="@xml/full_backup_rules"
```

```xml
<!-- res/xml/data_extraction_rules.xml -->
<data-extraction-rules>
  <cloud-backup><exclude domain="root" path="."/></cloud-backup>
  <device-transfer><exclude domain="root" path="."/></device-transfer>
</data-extraction-rules>
```

```xml
<!-- res/xml/full_backup_rules.xml -->
<full-backup-content><exclude domain="root" path="."/></full-backup-content>
```

仕様確認資料:
- https://developer.android.com/identity/data/autobackup
- https://developer.android.com/about/versions/14/changes/partial-photo-video-access
