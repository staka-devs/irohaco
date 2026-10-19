# Android OS バックアップ方針（実装ガイド）

Irohacoは既定では完全にローカルで動く。OSの自動バックアップと、アプリ自身がユーザーの操作で行う暗号化エクスポートは別のものである。

## 推奨

- データの保護を最優先し、初期リリースではOSのクラウドバックアップをオプトアウトする。
- 端末間の転送も自動のままだと、秘密情報や大きなキャッシュが転送されることがある。初期の実装では自動の移行を無効にし、手動の暗号化インポートを用意する。
- `AndroidManifest`で `android:allowBackup="false"` を指定し、あわせて Android 12以降の`android:dataExtractionRules`と、古いバージョン向けの`android:fullBackupContent`の設定を具体的に書く。
- Android OSやOEMによっては、`allowBackup=false` だけでは端末間の転送を必ず止められるとは限らない。ルールが効くかを実機で検証する。
- 以下は実装のひな形である。アプリのビルドに組み込む前に、ターゲットAPIと検証対象のOSで確かめる。

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

仕様を確かめた資料:
- https://developer.android.com/identity/data/autobackup
- https://developer.android.com/about/versions/14/changes/partial-photo-video-access
