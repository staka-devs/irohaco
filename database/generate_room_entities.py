"""Generate the Room entities and database class from schema.sql.

schema.sql stays the source of truth: the app creates its database by running it, and Room
validates these entities against that database when it opens (core:database tests).
Run from the repository root after changing schema.sql:
    python database/generate_room_entities.py
"""
import pathlib
import sqlite3

ROOT = pathlib.Path(__file__).resolve().parents[1]
DDL = ROOT / 'database' / 'schema.sql'
OUT = ROOT / 'core/database/src/main/kotlin/dev/irohaco/core/database/IrohacoDatabase.kt'
KOTLIN_TYPES = {'TEXT': 'String', 'INTEGER': 'Long', 'REAL': 'Double', 'BLOB': 'ByteArray'}
# Hand-written DAOs in core:database; add one here to expose it on IrohacoDatabase.
DAOS = ['CatalogDao', 'TimelineDao', 'LibraryDao', 'MarksDao', 'CollectionsDao', 'IdentityDao', 'LocationDao', 'ModelProfileDao']
FK_ACTIONS = {'NO ACTION': 'NO_ACTION', 'RESTRICT': 'RESTRICT', 'CASCADE': 'CASCADE', 'SET NULL': 'SET_NULL', 'SET DEFAULT': 'SET_DEFAULT'}


def pascal(name):
    return ''.join(p.capitalize() for p in name.split('_'))


def camel(name):
    p = pascal(name)
    return p[0].lower() + p[1:]


def kstr(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$') + '"'


def klist(items):
    return '[' + ', '.join(kstr(i) for i in items) + ']'


def entity(db, table):
    cols = db.execute(f"PRAGMA table_info('{table}')").fetchall()
    pks = [c[1] for c in sorted((c for c in cols if c[5]), key=lambda c: c[5])]
    args = [f'tableName = {kstr(table)}', f'primaryKeys = {klist(pks)}']

    fks = {}
    for fid, _seq, parent, child_col, parent_col, on_update, on_delete, _match in db.execute(f"PRAGMA foreign_key_list('{table}')"):
        fk = fks.setdefault(fid, {'parent': parent, 'child': [], 'parentCols': [], 'onUpdate': on_update, 'onDelete': on_delete})
        fk['child'].append(child_col)
        fk['parentCols'].append(parent_col)
    if fks:
        lines = []
        for fk in fks.values():
            parent_cols = fk['parentCols']
            if any(p is None for p in parent_cols):  # REFERENCES parent without columns targets its primary key
                parent_cols = [c[1] for c in sorted((c for c in db.execute(f"PRAGMA table_info('{fk['parent']}')") if c[5]), key=lambda c: c[5])]
            lines.append(
                f'        ForeignKey(entity = {pascal(fk["parent"])}Entity::class, parentColumns = {klist(parent_cols)}, '
                f'childColumns = {klist(fk["child"])}, onDelete = ForeignKey.{FK_ACTIONS[fk["onDelete"]]}, '
                f'onUpdate = ForeignKey.{FK_ACTIONS[fk["onUpdate"]]}),'
            )
        args.append('foreignKeys = [\n' + '\n'.join(lines) + '\n    ]')

    indices = db.execute(
        "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql IS NOT NULL ORDER BY name", (table,)
    ).fetchall()
    if indices:
        lines = []
        for (name,) in indices:
            unique = next(r[2] for r in db.execute(f"PRAGMA index_list('{table}')") if r[1] == name)
            idx_cols = [r[2] for r in db.execute(f"PRAGMA index_info('{name}')")]
            lines.append(f'        Index(value = {klist(idx_cols)}, name = {kstr(name)}, unique = {"true" if unique else "false"}),')
        args.append('indices = [\n' + '\n'.join(lines) + '\n    ]')

    fields = []
    for _cid, name, typ, notnull, default, pk in cols:
        info = [f'name = {kstr(name)}']
        if default is not None:
            info.append(f'defaultValue = {kstr(default)}')
        nullable = '' if (notnull or pk) else '?'
        fields.append(f'    @ColumnInfo({", ".join(info)}) val {camel(name)}: {KOTLIN_TYPES[typ]}{nullable},')

    return (
        f'@Entity(\n    ' + ',\n    '.join(args) + ',\n)\n'
        f'data class {pascal(table)}Entity(\n' + '\n'.join(fields) + '\n)\n'
    )


def main():
    db = sqlite3.connect(':memory:')
    db.executescript(DDL.read_text(encoding='utf-8'))
    tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
    body = '\n'.join(entity(db, t) for t in tables)
    entities = ',\n'.join(f'        {pascal(t)}Entity::class' for t in tables)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        '// Generated from database/schema.sql by database/generate_room_entities.py. Do not edit.\n'
        'package dev.irohaco.core.database\n\n'
        'import androidx.room.ColumnInfo\n'
        'import androidx.room.Database\n'
        'import androidx.room.Entity\n'
        'import androidx.room.ForeignKey\n'
        'import androidx.room.Index\n'
        'import androidx.room.RoomDatabase\n\n'
        '@Database(\n    entities = [\n' + entities + ',\n    ],\n    version = 1,\n    exportSchema = true,\n)\n'
        'abstract class IrohacoDatabase : RoomDatabase() {\n'
        + ''.join(f'    abstract fun {d[0].lower() + d[1:]}(): {d}\n' for d in DAOS)
        + '}\n\n' + body,
        encoding='utf-8',
        newline='\n',
    )
    print(f'{len(tables)} entities -> {OUT.relative_to(ROOT)}')


if __name__ == '__main__':
    main()
