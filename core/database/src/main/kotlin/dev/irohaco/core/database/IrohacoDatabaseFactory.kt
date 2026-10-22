package dev.irohaco.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.WorkerThread
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * Opens the app database (DATABASE_DESIGN §9, ADR-0005).
 *
 * Room entities cannot express CHECK constraints or triggers, so the database is never created from
 * the entities. On first launch it is built by running database/schema.sql (shipped as an asset)
 * and handed to Room as a pre-packaged database; Room then validates every entity against the real
 * tables (columns, types, NOT NULL, keys, foreign keys, indices) and fails to open on any mismatch.
 */
object IrohacoDatabaseFactory {
    const val NAME = "irohaco.db"
    private const val SCHEMA_ASSET = "schema.sql"

    /** [name] other than [NAME] lets tests keep their own file away from the app's open database. */
    @WorkerThread
    fun open(context: Context, name: String = NAME): IrohacoDatabase =
        open(context, context.assets.open(SCHEMA_ASSET).bufferedReader().use { it.readText() }, name)

    @WorkerThread
    internal fun open(context: Context, schemaSql: String, name: String): IrohacoDatabase {
        val builder = Room.databaseBuilder(context, IrohacoDatabase::class.java, name).addCallback(OnOpen)
        val bootstrap = if (context.getDatabasePath(name).exists()) null else createFromSchema(context, schemaSql, name)
        bootstrap?.let { builder.createFromFile(it) }
        val db = builder.build()
        try {
            db.openHelper.writableDatabase // copies and validates the bootstrap now, not on first query
        } catch (e: RuntimeException) {
            db.close()
            if (bootstrap != null) context.deleteDatabase(name) // a fresh copy holds no user data yet
            throw e
        } finally {
            bootstrap?.delete()
        }
        return db
    }

    private fun createFromSchema(context: Context, schemaSql: String, name: String): File {
        val file = File(context.cacheDir, "$name.bootstrap").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.beginTransaction()
            try {
                splitStatements(schemaSql).forEach { db.execSQL(it) }
                db.version = 1
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return file
    }

    // Foreign keys are off per connection unless asked for; ocr_fts is derived, so it is made here (M2-07).
    private object OnOpen : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(RoomTextIndexStore.CREATE_FTS)
        }
    }
}

/**
 * Splits schema.sql into single statements, because SQLiteDatabase.execSQL runs only one.
 * A trigger ends on a line that is exactly `END;` (or a one-line `BEGIN … END;`); a `CASE … END;`
 * line inside its body does not end it. Comment lines between statements are skipped.
 */
internal fun splitStatements(sql: String): List<String> {
    val statements = mutableListOf<String>()
    val current = StringBuilder()
    for (line in sql.lineSequence()) {
        val trimmed = line.trim()
        if (current.isEmpty() && (trimmed.isEmpty() || trimmed.startsWith("--"))) continue
        current.appendLine(line)
        if (!trimmed.endsWith(";")) continue
        val isTrigger = current.trimStart().startsWith("CREATE TRIGGER", ignoreCase = true)
        val endsTrigger = trimmed.equals("END;", ignoreCase = true) ||
            (trimmed.startsWith("BEGIN", ignoreCase = true) && trimmed.endsWith("END;", ignoreCase = true))
        if (!isTrigger || endsTrigger) {
            statements += current.toString().trim()
            current.clear()
        }
    }
    require(current.isBlank()) { "schema.sql ends inside a statement: ${current.lines().first()}" }
    return statements
}
