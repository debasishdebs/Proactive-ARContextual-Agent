package com.rokid.context.memory.place

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
import com.rokid.context.memory.DbKeyStore
import com.rokid.context.memory.db.DeferredOpenHelperFactory

/** One place in the pack. id is "wd:Q…", "osm:w…" or "user:…". Text only: the summary is at most [PlacePack.SUMMARY_MAX] characters. */
@Entity(tableName = "place", indices = [Index("lat", "lon"), Index("parentId"), Index("seeded", "pinned", "lastUsedDay")])
data class PlaceEntity(
    @PrimaryKey val id: String,
    val parentId: String?,
    val name: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
    val summary: String,
    /** "wikipedia:en", "wikidata", "osm" or "user". */
    val source: String,
    val sourceRev: String,
    val fetchedAt: Long,
    val seeded: Boolean,
    val pinned: Boolean,
    /** Days since the epoch when this place last served a card: the LRU key. A fetched place counts as used the day it is fetched; Delete all resets it to 0. */
    val lastUsedDay: Int,
    val bytes: Int,
)

/** A 5 km cell that was fetched. [status] is [CELL_OK] (all sources answered) or [CELL_PARTIAL] (Overpass did not: re-fetched after a day, not 30). */
@Entity(tableName = "cell")
data class CellEntity(@PrimaryKey val geohash5: String, val fetchedAt: Long, val status: String)

/** A blue download zone that was seeded. [status] is [AREA_DONE], [AREA_PARTIAL] or [AREA_SEEDING]. */
@Entity(tableName = "area")
data class AreaEntity(
    @PrimaryKey val blueZoneId: String,
    val south: Double, val west: Double, val north: Double, val east: Double,
    val seededAt: Long,
    val status: String,
) {
    val box: GeoBox get() = GeoBox(south, west, north, east)
}

const val CELL_OK = "OK"
const val CELL_PARTIAL = "PARTIAL"
const val AREA_DONE = "DONE"
const val AREA_PARTIAL = "PARTIAL"
const val AREA_SEEDING = "SEEDING"

@Dao
interface PlacesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPlaces(rows: List<PlaceEntity>)
    @Query("SELECT * FROM place WHERE id IN (:ids)") suspend fun byIds(ids: List<String>): List<PlaceEntity>
    @Query("SELECT * FROM place WHERE lat BETWEEN :south AND :north AND lon BETWEEN :west AND :east") suspend fun inBox(south: Double, north: Double, west: Double, east: Double): List<PlaceEntity>
    @Query("SELECT * FROM place WHERE parentId IN (:parentIds)") suspend fun childrenOf(parentIds: List<String>): List<PlaceEntity>
    @Query("UPDATE place SET lastUsedDay = :day WHERE id IN (:ids)") suspend fun touch(ids: List<String>, day: Int)
    @Query("UPDATE place SET seeded = 1 WHERE lat BETWEEN :south AND :north AND lon BETWEEN :west AND :east") suspend fun markSeeded(south: Double, north: Double, west: Double, east: Double): Int
    @Query("UPDATE place SET seeded = 0 WHERE seeded = 1 AND substr(id, 1, 5) <> 'user:'") suspend fun clearSeeded(): Int
    @Query("UPDATE place SET pinned = :pinned WHERE id = :id AND substr(id, 1, 5) <> 'user:'") suspend fun setPinned(id: String, pinned: Boolean): Int
    @Query("SELECT * FROM place WHERE substr(id, 1, 5) = 'user:'") suspend fun userPlaces(): List<PlaceEntity>
    @Query("DELETE FROM place WHERE substr(id, 1, 5) = 'user:' AND id NOT IN (:keep)") suspend fun deleteUserExcept(keep: List<String>): Int
    @Query("SELECT COUNT(*) FROM place WHERE pinned = 1") suspend fun pinnedCount(): Int
    @Query("SELECT COUNT(*) FROM place WHERE pinned = 0 AND seeded = 1") suspend fun seededCount(): Int
    @Query("SELECT COUNT(*) FROM place WHERE pinned = 0 AND seeded = 0") suspend fun fetchedCount(): Int
    @Query("SELECT COALESCE(SUM(bytes), 0) FROM place") suspend fun totalBytes(): Long
    @Query("SELECT COUNT(*) FROM place") suspend fun placeCount(): Int
    @Query("SELECT * FROM place WHERE seeded = 0 AND pinned = 0 AND id NOT IN (:keep) ORDER BY lastUsedDay ASC, fetchedAt ASC, id ASC LIMIT :n") suspend fun evictable(n: Int, keep: List<String>): List<PlaceEntity>
    @Query("DELETE FROM place WHERE id IN (:ids)") suspend fun deletePlaces(ids: List<String>): Int
    @Query("DELETE FROM place WHERE seeded = 0 AND pinned = 0") suspend fun deleteFetched(): Int
    @Query("UPDATE place SET lastUsedDay = 0") suspend fun resetRecency(): Int

    @Query("SELECT * FROM cell WHERE geohash5 = :cell") suspend fun cell(cell: String): CellEntity?
    @Query("SELECT * FROM cell") suspend fun cells(): List<CellEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCell(row: CellEntity)
    @Query("DELETE FROM cell WHERE geohash5 IN (:cells)") suspend fun deleteCells(cells: List<String>): Int

    @Query("SELECT * FROM area ORDER BY blueZoneId") suspend fun areas(): List<AreaEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putArea(row: AreaEntity)
    @Query("DELETE FROM area WHERE blueZoneId = :id") suspend fun deleteArea(id: String): Int
}

/**
 * The place pack's own database (spec 2026-11-16 §B3): `places.db`, Room v1, separate from the main `context.db` (which stays v16). Encrypted with SQLCipher under the SAME key as the
 * main database ([DbKeyStore]). It holds public place text only, never a position or a visit; it is excluded from backup and transfer (BackupRulesTest) and is never part of a settings export.
 */
@Database(entities = [PlaceEntity::class, CellEntity::class, AreaEntity::class], version = 1, exportSchema = true)
abstract class PlacesDatabase : RoomDatabase() {
    abstract fun dao(): PlacesDao

    companion object {
        const val NAME = "places.db"

        /** The app's pack database: opened lazily, encrypted, never on Main (the Keystore is read at first open). */
        fun build(context: Context, passphrase: () -> ByteArray = { DbKeyStore.passphrase(context) }): PlacesDatabase =
            Room.databaseBuilder(context.applicationContext, PlacesDatabase::class.java, NAME)
                .openHelperFactory(DeferredOpenHelperFactory({ true }, passphrase))
                .build()

        fun inMemory(context: Context): PlacesDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, PlacesDatabase::class.java).allowMainThreadQueries().build()
    }
}
