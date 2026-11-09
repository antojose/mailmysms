package com.antolabs.mailmysms

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Layer 1 (fast) dedup tracking for bulk history backups (SMS, MMS, call log): which local
 * provider row IDs have already been appended, per event type. This table is wiped if the app
 * is uninstalled, so it is not the only line of defense - the backup services also check what's
 * actually in the mailbox (Layer 2) before trusting this to be complete.
 */
class BackupTracker private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE imported (" +
                "event_type TEXT NOT NULL, " +
                "local_id INTEGER NOT NULL, " +
                "account_email TEXT NOT NULL, " +
                "message_id TEXT NOT NULL, " +
                "PRIMARY KEY (event_type, local_id, account_email))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS imported")
        onCreate(db)
    }

    // Keyed by (event_type, local_id, account_email) rather than just local_id, so backing up
    // to a test account first and later switching to a real account doesn't cause the real run
    // to see everything as "already imported" and silently skip it, and so SMS/MMS/call log
    // tracking never collides even if their local row IDs happen to coincide.
    fun isImported(eventType: String, localId: Long, accountEmail: String): Boolean {
        readableDatabase.query(
            "imported", arrayOf("local_id"), "event_type = ? AND local_id = ? AND account_email = ?",
            arrayOf(eventType, localId.toString(), accountEmail), null, null, null
        ).use { return it.moveToFirst() }
    }

    fun markImported(eventType: String, localId: Long, accountEmail: String, messageId: String) {
        val values = ContentValues().apply {
            put("event_type", eventType)
            put("local_id", localId)
            put("account_email", accountEmail)
            put("message_id", messageId)
        }
        writableDatabase.insertWithOnConflict("imported", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun importedCount(eventType: String, accountEmail: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM imported WHERE event_type = ? AND account_email = ?",
            arrayOf(eventType, accountEmail)
        ).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    companion object {
        const val TYPE_SMS = "sms"
        const val TYPE_MMS = "mms"
        const val TYPE_RCS = "rcs"
        const val TYPE_CALL = "call"

        private const val DB_NAME = "sms_backup_tracker.db"
        private const val DB_VERSION = 3

        @Volatile private var instance: BackupTracker? = null

        fun get(context: Context): BackupTracker =
            instance ?: synchronized(this) {
                instance ?: BackupTracker(context).also { instance = it }
            }
    }
}
