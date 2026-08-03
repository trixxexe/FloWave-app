package com.example.flowave.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "queue_items")
data class QueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val orderIndex: Int
)

@Entity(tableName = "queue_state")
data class QueueState(
    @PrimaryKey val id: Int = 1,
    val currentQueueIndex: Int,
    val currentPositionMs: Long
)
