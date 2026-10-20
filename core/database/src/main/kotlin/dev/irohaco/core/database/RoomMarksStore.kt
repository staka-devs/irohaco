package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import dev.irohaco.core.contracts.AssetMarks
import dev.irohaco.core.contracts.MarksStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface MarksDao {
    @Query("SELECT is_favorite AS favorite, rating FROM media_ratings WHERE asset_id = :assetId")
    fun observe(assetId: String): Flow<MarksRow?>

    @Query("UPDATE media_ratings SET is_favorite = :favorite WHERE asset_id = :assetId")
    suspend fun updateFavorite(assetId: String, favorite: Boolean): Int

    @Query("INSERT INTO media_ratings(asset_id, rating, is_favorite) VALUES (:assetId, NULL, :favorite)")
    suspend fun insertFavorite(assetId: String, favorite: Boolean)

    @Query("UPDATE media_ratings SET rating = :rating WHERE asset_id = :assetId")
    suspend fun updateRating(assetId: String, rating: Int?): Int

    @Query("INSERT INTO media_ratings(asset_id, rating, is_favorite) VALUES (:assetId, :rating, 0)")
    suspend fun insertRating(assetId: String, rating: Int?)

    @Transaction
    suspend fun setFavorite(assetIds: Collection<String>, favorite: Boolean) {
        for (id in assetIds) if (updateFavorite(id, favorite) == 0) insertFavorite(id, favorite)
    }

    @Transaction
    suspend fun setRating(assetId: String, rating: Int?) {
        if (updateRating(assetId, rating) == 0) insertRating(assetId, rating)
    }
}

data class MarksRow(val favorite: Boolean, val rating: Int?)

class RoomMarksStore(private val db: IrohacoDatabase) : MarksStore {
    override fun marks(assetId: String): Flow<AssetMarks> =
        db.marksDao().observe(assetId).map { row -> row?.let { AssetMarks(it.favorite, it.rating) } ?: AssetMarks() }

    override suspend fun setFavorite(assetIds: Collection<String>, favorite: Boolean) = db.marksDao().setFavorite(assetIds, favorite)

    override suspend fun setRating(assetId: String, rating: Int?) {
        require(rating == null || rating in 1..5) { "rating $rating" }
        db.marksDao().setRating(assetId, rating)
    }
}
