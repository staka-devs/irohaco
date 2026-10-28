# ADR-0012: 最小のOSをAndroid 13にし、JDK 21でビルドする

- 日付: 2026-10-20
- 状態: Accepted
- 関連: ARCHITECTURE §1.1、PRD §14、#1、#68

## Context
アプリが動くAndroidのバージョンの下限と、ビルドとテストに使うJDKを決める必要がある。PRD §14では、最小のAPIはAndroid 13（API 33）を候補としていた。テストにはJVMの上でAndroidを再現するRobolectricを使う。RobolectricはAndroid SDK 35以降の環境を作るのにJava 21を必要とする。

## Decision
- minSdkは33、compileSdkとtargetSdkは37にする。
- ビルドとCIのJDKは21にする。純粋なKotlinのモジュール（`core:domain`、`core:contracts`）は、これまでどおりJava 17のバイトコードを出力する。
- ビルドの環境は`.devcontainer/`のDockerイメージでそろえる。

## Consequences
Android 13以降を前提にできるので、Dynamic Colorを常に使える。Robolectricのテストを、targetSdkと同じSDK 37で動かせる。Android 12以前の端末ではアプリをインストールできない。基準端末と依存するランタイムの確認で下限を変えるときは、ARCHITECTURE §1.1とPRD §14を直す。

## Rejected alternatives
- JDK 17のままにする: Robolectricのテストを、SDK 34に固定して動かすしかない。
