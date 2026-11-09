package com.antolabs.mailmysms

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class EventRecord(
    val id: Long,
    val type: String,
    val summary: String,
    val success: Boolean,
    val timestamp: Long,
    // Specifically a mailbox-login rejection (javax.mail.AuthenticationFailedException), as
    // opposed to a network/transient failure - lets the UI say "sign-in needs attention" instead
    // of a generic "delivery failed" when that's precisely what happened.
    val authFailure: Boolean = false
)

class EventLog private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "type TEXT NOT NULL, " +
                "summary TEXT NOT NULL, " +
                "success INTEGER NOT NULL, " +
                "timestamp INTEGER NOT NULL, " +
                "auth_failure INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS events")
        onCreate(db)
    }

    fun record(
        type: String,
        summary: String,
        success: Boolean,
        timestamp: Long = System.currentTimeMillis(),
        authFailure: Boolean = false
    ) {
        val values = ContentValues().apply {
            put("type", type)
            put("summary", summary)
            put("success", if (success) 1 else 0)
            put("timestamp", timestamp)
            put("auth_failure", if (authFailure) 1 else 0)
        }
        writableDatabase.insert("events", null, values)
        trim()
    }

    private fun trim() {
        val cutoff = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000
        writableDatabase.delete("events", "timestamp < ?", arrayOf(cutoff.toString()))
    }

    fun countSince(sinceMillis: Long, type: String? = null, successOnly: Boolean = false): Int {
        val where = StringBuilder("timestamp >= ?")
        val args = mutableListOf(sinceMillis.toString())
        if (type != null) {
            where.append(" AND type = ?")
            args.add(type)
        }
        if (successOnly) {
            where.append(" AND success = 1")
        }
        readableDatabase.query(
            "events", arrayOf("COUNT(*)"), where.toString(), args.toTypedArray(), null, null, null
        ).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun recent(limit: Int): List<EventRecord> {
        val list = mutableListOf<EventRecord>()
        readableDatabase.query(
            "events", null, null, null, null, null, "timestamp DESC", limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    EventRecord(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
                        success = cursor.getInt(cursor.getColumnIndexOrThrow("success")) == 1,
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                        authFailure = cursor.getInt(cursor.getColumnIndexOrThrow("auth_failure")) == 1
                    )
                )
            }
        }
        return list
    }

    /** Most recent event across all types that actually succeeded - "last confirmed successful
     *  mailbox write", not just the most recent attempt regardless of outcome. */
    fun mostRecentSuccess(): EventRecord? {
        readableDatabase.query(
            "events", null, "success = 1", null, null, null, "timestamp DESC", "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return EventRecord(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
                success = true,
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                authFailure = false
            )
        }
    }

    companion object {
        private const val DB_NAME = "event_log.db"
        private const val DB_VERSION = 2

        @Volatile private var instance: EventLog? = null

        fun get(context: Context): EventLog =
            instance ?: synchronized(this) {
                instance ?: EventLog(context).also { instance = it }
            }
    }
}
