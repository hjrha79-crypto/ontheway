package com.vita.ontheway

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

data class StateTransition(
    val id: Long = 0,
    val sessionId: String,
    val eventId: String,
    val fromState: String,
    val toState: String,
    val trigger: String,
    val timestamp: Long
)

class StateTransitionLog(context: Context) : SQLiteOpenHelper(
    context, "state_transition.db", null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE state_transitions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId TEXT NOT NULL,
                eventId TEXT NOT NULL,
                fromState TEXT NOT NULL,
                toState TEXT NOT NULL,
                trigger_name TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_session ON state_transitions(sessionId)")
        db.execSQL("CREATE INDEX idx_event ON state_transitions(eventId)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}

    fun record(transition: StateTransition) {
        val values = ContentValues().apply {
            put("sessionId", transition.sessionId)
            put("eventId", transition.eventId)
            put("fromState", transition.fromState)
            put("toState", transition.toState)
            put("trigger_name", transition.trigger)
            put("timestamp", transition.timestamp)
        }
        writableDatabase.insert("state_transitions", null, values)

        // Logcat에도 동시 출력 (실시간 추적용)
        Log.d("SessionTransition",
            "[${transition.eventId.take(8)}] ${transition.fromState} → ${transition.toState} " +
            "(trigger=${transition.trigger}, t=${transition.timestamp})")
    }

    fun getTransitionsForEvent(eventId: String): List<StateTransition> {
        val cursor = readableDatabase.query(
            "state_transitions", null,
            "eventId = ?", arrayOf(eventId),
            null, null, "timestamp ASC"
        )
        val list = mutableListOf<StateTransition>()
        while (cursor.moveToNext()) {
            list.add(StateTransition(
                id = cursor.getLong(0),
                sessionId = cursor.getString(1),
                eventId = cursor.getString(2),
                fromState = cursor.getString(3),
                toState = cursor.getString(4),
                trigger = cursor.getString(5),
                timestamp = cursor.getLong(6)
            ))
        }
        cursor.close()
        return list
    }

    fun clearOld(beforeTimestamp: Long) {
        writableDatabase.delete("state_transitions", "timestamp < ?", arrayOf(beforeTimestamp.toString()))
    }
}
