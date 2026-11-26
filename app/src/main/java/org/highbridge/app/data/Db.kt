// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A conversation is "PROTOCOL:ch:<index>" for a channel or "PROTOCOL:dm:<nodeId>" for a direct chat. */
@Entity(tableName = "messages", indices = [Index("conversation"), Index("packetId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val packetId: String,
    val protocol: String,
    val conversation: String,
    val fromId: String,
    val fromName: String,
    val text: String,
    val timeMs: Long,
    val outgoing: Boolean,
    /** 0 sending, 1 sent, 2 delivered, 3 failed, 4 received */
    val status: Int = 4,
    val mediaType: String? = null,
    val mediaPath: String? = null,
    val snr: Float? = null,
    val viaBridge: Boolean = false,
    val read: Boolean = outgoing,
)

@Entity(tableName = "nodes")
data class NodeEntity(
    @PrimaryKey val key: String, // PROTOCOL:id
    val protocol: String,
    val id: String,
    val shortName: String,
    val longName: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val battery: Int? = null,
    val voltage: Float? = null,
    val snr: Float? = null,
    val rssi: Int? = null,
    val hops: Int? = null,
    val lastHeardMs: Long = 0,
    val favorite: Boolean = false,
)

@Entity(tableName = "telemetry", indices = [Index("nodeKey")])
data class TelemetryEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val nodeKey: String, val timeMs: Long,
    val battery: Int? = null, val voltage: Float? = null, val channelUtil: Float? = null, val airUtilTx: Float? = null,
    val temperatureC: Float? = null, val humidityPct: Float? = null, val pressureHpa: Float? = null,
)

@Entity(tableName = "range_test")
data class RangeTestEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val protocol: String, val fromId: String, val text: String, val snr: Float?, val rssi: Int?,
    val lat: Double?, val lon: Double?, val timeMs: Long,
)

@Entity(tableName = "track_points", indices = [Index("trackId")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val trackId: Long, val lat: Double, val lon: Double, val altM: Double?, val timeMs: Long,
)

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val protocol: String, val destId: String, val hops: String, val snrs: String, val hopsBack: String, val snrsBack: String, val timeMs: Long,
)

/** CIRCLE: points = "lat,lon", radiusM. POLYGON: points = "lat,lon;lat,lon;...". TRAIL: drift alert when a node leaves a corridor. */
@Entity(tableName = "geofences")
data class GeofenceEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val name: String, val kind: String, val points: String, val radiusM: Double, val nodeKey: String?, val active: Boolean = true,
    val alertOnExit: Boolean = true,
)

/** Channel keys made or imported in the app. */
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String, val protocol: String, val slot: Int, val name: String, val keyB64: String, val createdMs: Long,
)

data class ConversationRow(val conversation: String, val protocol: String, val lastText: String, val lastFrom: String, val lastTime: Long, val unread: Int)

@Dao
interface MeshDao {
    @Insert suspend fun insertMessage(m: MessageEntity): Long
    @Query("UPDATE messages SET status = :status WHERE packetId = :packetId AND outgoing = 1") suspend fun setStatus(packetId: String, status: Int)
    @Query("UPDATE messages SET packetId = :newId, status = :status WHERE rowId = :rowId") suspend fun setSent(rowId: Long, newId: String, status: Int)
    @Query("SELECT * FROM messages WHERE conversation = :c ORDER BY timeMs ASC") fun messages(c: String): Flow<List<MessageEntity>>
    @Query("SELECT COUNT(*) FROM messages WHERE packetId = :packetId AND protocol = :protocol") suspend fun countPacket(packetId: String, protocol: String): Int
    @Query("UPDATE messages SET read = 1 WHERE conversation = :c") suspend fun markRead(c: String)
    @Query("""SELECT m.conversation AS conversation, m.protocol AS protocol, m.text AS lastText, m.fromName AS lastFrom, m.timeMs AS lastTime,
              (SELECT COUNT(*) FROM messages u WHERE u.conversation = m.conversation AND u.read = 0) AS unread
              FROM messages m WHERE m.timeMs = (SELECT MAX(timeMs) FROM messages x WHERE x.conversation = m.conversation)
              GROUP BY m.conversation ORDER BY m.timeMs DESC""") fun conversations(): Flow<List<ConversationRow>>
    @Query("SELECT * FROM messages ORDER BY timeMs DESC LIMIT :n") suspend fun recentMessages(n: Int): List<MessageEntity>

    @Upsert suspend fun upsertNode(n: NodeEntity)
    @Query("SELECT * FROM nodes WHERE `key` = :key") suspend fun node(key: String): NodeEntity?
    @Query("SELECT * FROM nodes ORDER BY lastHeardMs DESC") fun nodes(): Flow<List<NodeEntity>>
    @Query("SELECT * FROM nodes ORDER BY lastHeardMs DESC") suspend fun nodesNow(): List<NodeEntity>
    @Query("UPDATE nodes SET favorite = :fav WHERE `key` = :key") suspend fun setFavorite(key: String, fav: Boolean)

    @Insert suspend fun insertTelemetry(t: TelemetryEntity)
    @Query("SELECT * FROM telemetry WHERE nodeKey = :key ORDER BY timeMs DESC LIMIT 500") fun telemetry(key: String): Flow<List<TelemetryEntity>>
    @Query("SELECT * FROM telemetry WHERE pressureHpa IS NOT NULL AND timeMs > :since ORDER BY timeMs ASC") suspend fun pressureSince(since: Long): List<TelemetryEntity>

    @Insert suspend fun insertRange(r: RangeTestEntity)
    @Query("SELECT * FROM range_test ORDER BY timeMs DESC") fun rangeTests(): Flow<List<RangeTestEntity>>
    @Query("DELETE FROM range_test") suspend fun clearRange()

    @Insert suspend fun insertTrackPoint(p: TrackPointEntity)
    @Query("SELECT * FROM track_points WHERE trackId = :id ORDER BY timeMs ASC") suspend fun track(id: Long): List<TrackPointEntity>
    @Query("SELECT DISTINCT trackId FROM track_points ORDER BY trackId DESC") fun trackIds(): Flow<List<Long>>
    @Query("DELETE FROM track_points WHERE trackId = :id") suspend fun deleteTrack(id: Long)

    @Insert suspend fun insertRoute(r: RouteEntity)
    @Query("SELECT * FROM routes ORDER BY timeMs DESC LIMIT 200") fun routes(): Flow<List<RouteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertGeofence(g: GeofenceEntity): Long
    @Query("SELECT * FROM geofences") fun geofences(): Flow<List<GeofenceEntity>>
    @Query("SELECT * FROM geofences WHERE active = 1") suspend fun activeGeofences(): List<GeofenceEntity>
    @Query("DELETE FROM geofences WHERE rowId = :id") suspend fun deleteGeofence(id: Long)

    @Upsert suspend fun upsertChannel(c: ChannelEntity)
    @Query("SELECT * FROM channels ORDER BY createdMs DESC") fun channels(): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels") suspend fun channelsNow(): List<ChannelEntity>
    @Query("DELETE FROM channels WHERE id = :id") suspend fun deleteChannel(id: String)
}

@Database(entities = [MessageEntity::class, NodeEntity::class, TelemetryEntity::class, RangeTestEntity::class, TrackPointEntity::class,
    RouteEntity::class, GeofenceEntity::class, ChannelEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): MeshDao
    companion object {
        const val NAME = "highbridge.db"
        fun build(ctx: Context) = Room.databaseBuilder(ctx, AppDatabase::class.java, NAME).fallbackToDestructiveMigration().build()
    }
}
