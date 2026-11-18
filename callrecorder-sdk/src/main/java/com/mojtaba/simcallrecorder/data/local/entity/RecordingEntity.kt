package com.mojtaba.simcallrecorder.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recordings",
    indices = [Index(value = ["startedAtEpochMillis"])],
)
internal data class RecordingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startedAtEpochMillis: Long,
    val durationMillis: Long?,
    /**
     * Nullable by design: call-state APIs do not universally expose the phone number.
     * This value must only be populated when collection is lawful and explicitly enabled.
     */
    val phoneNumber: String?,
    /**
     * Generated filename within app-private storage, not an arbitrary filesystem path.
     */
    val audioFileName: String,
    val audioSource: String,
)
