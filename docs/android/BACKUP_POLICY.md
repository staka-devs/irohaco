# Android OS バックアップ方針（実装ガイド）

Irohacoは既定では完全にローカルで動く。OSの自動バックアップと、アプリ自身がユーザーの操作で行う暗号化エクスポートは別のものである。

## 方針

- データの保護を最優先し、初期リリースではOSのクラウドバックアップをオプトアウトする。
- 端末間の転送も自動のままだと、秘密情報や大きなキャッシュが転送されることがある。初期の実装では自動の移行を無効にし、手動の暗号化インポートを用意する。
- Android 12以降では、`android:allowBackup="false"`だけでは端末間の転送を止められない。そのため`android:dataExtractionRules`で、クラウドバックアップと端末間の転送の両方からアプリのデータを除外する。
- minSdkは33なので、Android 11以前でだけ使われる`android:fullBackupContent`は設定しない。
- 実装は`app/src/main/AndroidManifest.xml`と`app/src/main/res/xml/data_extraction_rules.xml`にある。

```xml
<!-- AndroidManifest.xml <application> attributes -->
android:allowBackup="false"
android:dataExtractionRules="@xml/data_extraction_rules"
```

```xml
<!-- res/xml/data_extraction_rules.xml -->
<data-extraction-rules>
  <cloud-backup>
    <exclude domain="root" path="."/>
    <exclude domain="external" path="."/>
  </cloud-backup>
  <device-transfer>
    <exclude domain="root" path="."/>
    <exclude domain="external" path="."/>
  </device-transfer>
</data-extraction-rules>
```

`root`はアプリのデータのディレクトリ全体（files、databases、shared_prefs）で、`external`は`getExternalFilesDir`である。

## 確認手順

OSやメーカーによっては設定が効かないことがあるので、基準端末で確かめる。アプリを入れて写真を1枚以上同期し、DBができた状態で行う。端末間の転送の確認には、Google Playの入った端末かエミュレーターが必要である。

### クラウドバックアップ

ローカルのトランスポートで代用する。

```bash
adb shell bmgr enable true
adb shell bmgr transport com.android.localtransport/.LocalTransport
adb shell bmgr backupnow dev.irohaco
```

### 端末間の転送

テストモードで行う。

```bash
adb shell settings put secure backup_enable_d2d_test_mode 1
adb shell bmgr transport com.google.android.gms/.backup.migrate.service.D2dTransport
adb shell bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport
adb shell bmgr backupnow dev.irohaco
```

### 合格の条件

それぞれのバックアップのあとに、アプリを消して入れ直す。入れ直すときにOSは選んだトランスポートから復元するが、まだアプリを起動しない状態で次のコマンドを実行し、`databases`に何もなければ合格とする（デバッグビルドで行う）。

```bash
adb shell pm uninstall --user 0 dev.irohaco
adb install app-debug.apk
adb shell run-as dev.irohaco ls databases
```

確認が終わったら、トランスポートとテストモードを元に戻す。

```bash
adb shell settings put secure backup_enable_d2d_test_mode 0
adb shell bmgr transport com.google.android.gms/.backup.BackupTransportService
```

## 参考資料

- https://developer.android.com/identity/data/autobackup
- https://developer.android.com/identity/data/testingbackup
- https://developer.android.com/about/versions/14/changes/partial-photo-video-access
