# ADR-0014: DBは参照DDLから作ってRoomに渡し、問い合わせの遅さはスキーマを変えずに直す

- 日付: 2026-10-19
- 状態: Accepted
- 関連: ADR-0005（第三正規形）、DATABASE_DESIGN §9、ARCHITECTURE §3・§13、#7、#163

## Context
DBの正本は`database/schema.sql`で、CHECK制約とトリガーで不正な行を拒む。RoomのエンティティではCHECK制約とトリガーを表せない。また、Roomは開くときにエンティティと実際のテーブルを照合し、索引を含めてずれがあればDBを開かない。

## Decision
- アプリはエンティティからDBを作らない。初回の起動で`schema.sql`をそのまま実行してDBを作り、`createFromFile`でRoomに渡す。照合に失敗したときは、作ったDBを消す。
- エンティティは`database/generate_room_entities.py`で`schema.sql`から生成する。
- 接続を開くたびに`PRAGMA foreign_keys=ON`を実行する。
- 全文検索の表（`ocr_fts`、`annotation_fts`）はRoomのエンティティにしない派生の表とし、DBを開くときに`CREATE VIRTUAL TABLE IF NOT EXISTS`で作る。
- SQLiteが遅い索引を選んだ問い合わせは、スキーマと索引を変えずに、問い合わせの書き方で直す。例えば、使わせたくない索引の列に単項の`+`を付ける。結合の順を固定したいときは`CROSS JOIN`を使う。

## Consequences
制約とトリガーをDBに持たせたまま、Roomの型付きの問い合わせを使える。スキーマを変えないので、インストール済みのDBをそのまま開ける。問い合わせの書き方による指定は意図が見えにくいので、コードにコメントで理由を残し、`EXPLAIN QUERY PLAN`を確かめるテストを置く。

## Rejected alternatives
- エンティティからDBを作る: CHECK制約とトリガーが失われる。
- 索引を足すか変える: Roomの照合に失敗し、インストール済みのDBを開けなくなる。
