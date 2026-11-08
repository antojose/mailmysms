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
    val timestamp: Long
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
                "timestamp INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS events")
        onCreate(db)
    }

    fun record(type: String, summary: String, success: Boolean, timestamp: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("type", type)
            put("summary", summary)
            put("success", if (success) 1 else 0)
            put("timestamp", timestamp)
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
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))
                    )
                )
            }
        }
        return list
    }

    companion object {
        private const val DB_NAME = "event_log.db"
        private const val DB_VERSION = 1

        @Volatile private var instance: EventLog? = null

        fun get(context: Context): EventLog =
            instance ?: synchronized(this) {
                instance ?: EventLog(context).also { instance = it }
            }
    }
}
