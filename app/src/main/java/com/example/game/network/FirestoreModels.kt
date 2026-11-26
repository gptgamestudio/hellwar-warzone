package com.example.game.network

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class UserCampaignProfile(
    val userId: String = "",
    val callsign: String = "VIPER-7",
    val credits: Long = 18500L,
    val highScore: Long = 0L,
    val humanDestroyedCount: Long = 0L,
    val orcDestroyedCount: Long = 0L,
    val dragonDestroyedCount: Long = 0L,
    val alienDestroyedCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class DestroyedBarracksRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val barracksId: String = "",
    val empire: String = "",
    val barracksIndex: Long = 0L,
    val damageDealt: Double = 0.0,
    val creditsAwarded: Long = 0L,
    val scoreAwarded: Long = 0L,
    @ServerTimestamp val destroyedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class HeadquartersVictoryRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val empire: String = "",
    val bossId: String = "",
    val creditsAwarded: Long = 0L,
    val scoreAwarded: Long = 0L,
    @ServerTimestamp val victoryAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class LeaderboardRecord(
    @DocumentId val id: String = "",
    val callsign: String = "VIPER-7",
    val highScore: Long = 0L,
    val totalBarracksDestroyed: Long = 0L,
    val hqClearedCount: Long = 0L,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
