# Irohaco 開発規約（Issue駆動・Git Flow・設計原則）

- 文書ID: IRO-DEV-001
- バージョン: 1.0.0
- 作成日: 2026-10-19
- 状態: 確定
- 関連: [`docs/plan/ROADMAP.md`](./docs/plan/ROADMAP.md)（Issue計画）、[`ARCHITECTURE.md`](./ARCHITECTURE.md)、[`DESIGN.md`](./DESIGN.md)

## 1. Issue駆動開発

1. 作業はすべてIssueから始める。Issueのない変更はマージしない。誤字の修正のような小さな変更は除く。
2. Issueには目的、受け入れ条件、対応するPRDの要求IDかADR、今回作らないものを書く。テンプレートは`.github/ISSUE_TEMPLATE/`にある。
3. 1つのIssueに1つのfeatureブランチと1つのPRを対応させ、PR本文に`Closes #<番号>`と書く。
4. 差分が800行を超えそうなIssueや、複数のモジュールにまたがる独立した変更を含むIssueは分割する。親Issueにはタスクリストで子Issueを並べる。
5. 仕様に影響する判断が出たら、実装の前に、同じPRで設計書（PRD、ARCHITECTURE、DESIGN、ADR）を直す。設計書はバージョンを上げずにその場で直す。リリース後の変更はADRで扱う。

### 1.1 ラベル

| 系統 | ラベル |
|---|---|
| 種類 | `type:feature` `type:bug` `type:chore` `type:spike` `type:docs` `type:adr` `type:test` |
| 領域 | `area:app` `area:designsystem` `area:domain` `area:database` `area:media` `area:viewer` `area:search` `area:indexing` `area:ai` `area:metadata` `area:api` `area:backup` `area:ci` |
| 優先度 | `R0`（初期リリースに必須） `R1` `R2` |
| 状態 | `blocked` `needs-design` |

### 1.2 マイルストーン

PRD §13のM0〜M4と`R0 Release`を、GitHubのMilestoneとして使う。どのIssueをどのMilestoneに入れるかは[`docs/plan/ROADMAP.md`](./docs/plan/ROADMAP.md)に従う。

## 2. ブランチ戦略（Git Flow）

```text
main      ──●──────────────────────●──────────●──   リリース済みのみ（タグ vX.Y.Z）
             \                    / \        /
hotfix/*      \                  /   ●──────●        本番不具合の緊急修正
               \                /             \
release/*       \        ●────●                \     リリース前の安定化
                 \      /                       \
develop   ────●───●──●─●──────────────●──────────●─   統合ブランチ（次リリースの最新）
               \    /  \             /
feature/*       ●──●    ●───●───●──●                 Issue単位の作業
```

| ブランチ | 派生元 | マージ先 | 命名 | マージ方法 |
|---|---|---|---|---|
| `main` | なし | なし | なし | 直接pushしない |
| `develop` | `main` | なし | なし | 直接pushしない |
| feature | `develop` | `develop` | `feature/<issue番号>-<短い英語>`（例: `feature/12-timeline-grid`） | Squash merge |
| spike | `develop` | マージしない。結果はIssueと`docs/verification/`に残す | `spike/<issue番号>-<短い英語>` | なし |
| release | `develop` | `main`と`develop` | `release/<X.Y.Z>` | Merge commit |
| hotfix | `main` | `main`と`develop` | `hotfix/<X.Y.Z>` | Merge commit |

- `main`にマージしたら`vX.Y.Z`のタグを付け、GitHub Releaseを作る。アプリの`versionName`はタグと同じにする。
- `release/*`では、不具合の修正、リリースノート、バージョン番号の変更だけを行う。機能は追加しない。
- `main`と`develop`にはブランチ保護を設定する。PRを必須にし、CIの成功を必須にし、force pushを禁止する。
- バージョンはSemantic Versioningに従い、R0リリースを`1.0.0`とする。

### 2.1 コミットメッセージ

コミットメッセージは[Conventional Commits](https://www.conventionalcommits.org/)の形式`<type>(<scope>): <要約>`で書く。typeには`feat` `fix` `refactor` `test` `docs` `build` `ci` `chore` `perf`を使い、scopeにはモジュール名を書く（例: `feat(timeline): add pinch to change grid density`）。

## 3. 設計原則

### 3.1 クリーンアーキテクチャ

- 依存の向きは Presentation → Application → Domain ← Infrastructure とする（[`ARCHITECTURE.md`](./ARCHITECTURE.md) §1）。内側の層は外側の層を参照しない。
- `core/domain`、`core/contracts`、`core/application`は、Android Framework、Room、LiteRT-LM、HTTPクライアントに依存しない純粋なKotlinモジュールにする。この制約はGradleの依存関係で守らせる。
- MediaStore、DB、推論、HTTP、ファイルとのやり取りは、`core/contracts`のPort（interface）で抽象化し、`infrastructure/*`のAdapterで実装する。DIの組み立ては`app`で行う。
- UIは`StateFlow<UiState>`による単方向のデータフローにする。ViewModelはUseCaseだけを呼び、RepositoryやDAOを直接呼ばない。
- DBのEntity、ネットワークのDTO、ドメインモデルは別のクラスにし、層をまたぐときに変換する。

### 3.2 SOLID

| 原則 | Irohacoでの当てはめ方 |
|---|---|
| S 単一責任 | UseCaseは1つの操作に1つのクラスにする（例: `StartAiIndexing`）。ViewModelは1つの画面の状態だけを扱う |
| O 開放閉鎖 | Embeddingや生成のプロバイダー、APIの形式、メタデータの形式は、Adapterを追加して増やす。既存のUseCaseは変えない |
| L リスコフの置換 | 同じPortの実装（ローカルとリモート、Fakeと本物）は、同じ契約テストに合格させる |
| I インターフェース分離 | Portは用途ごとに小さく分ける（例: `EmbeddingPort`と`GenerationPort`を分ける）。使わないメソッドを実装させない |
| D 依存性逆転 | UseCaseはPortに依存させ、具象クラスには依存させない。インスタンスは`app`のDIモジュールで作る |

### 3.3 YAGNI

- 作るのは、着手しているIssueの受け入れ条件に必要なものだけにする。将来に備えた抽象化、設定項目、汎用化は、2つ目の具体的な利用箇所ができたときに行う。
- モジュールは最初に必要になったIssueで作る。[`ARCHITECTURE.md`](./ARCHITECTURE.md) §1.1のモジュール構成は最終的な形を示したもので、最初から全部は作らない。
- Rustによるネイティブ化、ANN索引、R1とR2の機能は、計測の結果かIssueで必要だと分かるまで実装しない。
- 設計書で確定した事項（3NF、外部APIは既定で無効、動画の区間検索など）は、YAGNIを理由に後回しにしない。最初から守る。
- YAGNIとSOLIDのどちらを取るか迷ったら、層の境界にあるPortは作り、層の内側の抽象化は必要になるまで作らない。

## 4. 完了の条件（Definition of Done）

- [ ] Issueの受け入れ条件をすべて満たした
- [ ] ユニットテストを追加した（DomainとUseCaseは必須。PortのFakeを使う）
- [ ] CIが成功した（ビルド、Lint、ユニットテスト、参照DDLのテスト、スクリーンショットテスト）
- [ ] 層の依存の向きに反していない
- [ ] UIの変更は[`DESIGN.md`](./DESIGN.md) §10のチェックリストを満たした
- [ ] DBの変更は[`database/SCHEMA_REVIEW_TEMPLATE.md`](./database/SCHEMA_REVIEW_TEMPLATE.md)でレビューした
- [ ] 外部と通信する変更には、EgressPolicyのテストがある
- [ ] 必要な設計書を直した

## 5. テストの層

テストは[`ARCHITECTURE.md`](./ARCHITECTURE.md) §13.0の層に分ける。L1（JVMとホストのSQLite）とL2（エミュレーター）はPRごとにCIで動かす。L3（基準端末）はマイルストーンごとに行い、結果をIssueに残す。
