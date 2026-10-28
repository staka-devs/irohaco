package dev.irohaco.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import dev.irohaco.core.contracts.Album
import dev.irohaco.core.contracts.CollectionsStore
import dev.irohaco.core.contracts.Tag
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface CollectionsDao {
    @Query(
        """SELECT t.tag_id AS tagId, t.name AS name, count(mt.asset_id) AS itemCount
           FROM tags t LEFT JOIN media_tags mt ON mt.tag_id = t.tag_id
           GROUP BY t.tag_id ORDER BY t.name COLLATE NOCASE""",
    )
    fun tags(): Flow<List<Tag>>

    @Query(
        """SELECT t.tag_id AS tagId, t.name AS name, 0 AS itemCount
           FROM media_tags mt JOIN tags t ON t.tag_id = mt.tag_id
           WHERE mt.asset_id = :assetId ORDER BY t.name COLLATE NOCASE""",
    )
    fun tagsOf(assetId: String): Flow<List<Tag>>

    // A NULL parent never collides in a UNIQUE index, so top-level names are matched here.
    @Query("SELECT tag_id FROM tags WHERE parent_tag_id IS NULL AND name = :name")
    suspend fun tagIdByName(name: String): String?

    @Query("INSERT INTO tags(tag_id, name, parent_tag_id) VALUES (:tagId, :name, NULL)")
    suspend fun insertTag(tagId: String, name: String)

    @Query("INSERT OR IGNORE INTO media_tags(asset_id, tag_id) VALUES (:assetId, :tagId)")
    suspend fun tag(assetId: String, tagId: String)

    @Query("DELETE FROM media_tags WHERE asset_id = :assetId AND tag_id = :tagId")
    suspend fun untag(assetId: String, tagId: String)

    @Transaction
    suspend fun addTag(assetIds: Collection<String>, name: String, newId: String) {
        val tagId = tagIdByName(name) ?: newId.also { insertTag(it, name) }
        assetIds.forEach { tag(it, tagId) }
    }

    @Query(
        """SELECT al.album_id AS albumId, al.title AS title,
                  (SELECT count(*) FROM album_items ai WHERE ai.album_id = al.album_id
                     AND EXISTS (SELECT 1 FROM media_sources s WHERE s.asset_id = ai.asset_id AND +s.access_state = 'AVAILABLE')) AS itemCount,
                  (SELECT s.content_uri FROM album_items ai JOIN media_sources s ON s.asset_id = ai.asset_id
                     WHERE ai.album_id = al.album_id AND s.access_state = 'AVAILABLE'
                     ORDER BY ai.sort_order LIMIT 1) AS coverUri
           FROM albums al WHERE al.album_kind = 'MANUAL' ORDER BY al.title COLLATE NOCASE""",
    )
    fun albums(): Flow<List<Album>>

    @Query("INSERT INTO albums(album_id, title, album_kind, parent_album_id) VALUES (:albumId, :title, 'MANUAL', NULL)")
    suspend fun insertAlbum(albumId: String, title: String)

    @Query("SELECT COALESCE(max(sort_order), -1) FROM album_items WHERE album_id = :albumId")
    suspend fun lastOrder(albumId: String): Long

    @Query("INSERT OR IGNORE INTO album_items(album_id, asset_id, sort_order) VALUES (:albumId, :assetId, :order)")
    suspend fun insertItem(albumId: String, assetId: String, order: Long): Long

    @Transaction
    suspend fun addToAlbum(albumId: String, assetIds: List<String>) {
        var order = lastOrder(albumId)
        for (id in assetIds) if (insertItem(albumId, id, order + 1) != -1L) order++
    }
}

class RoomCollectionsStore(private val db: IrohacoDatabase, private val newId: () -> String = { UUID.randomUUID().toString() }) : CollectionsStore {
    private val dao get() = db.collectionsDao()

    override fun tags(): Flow<List<Tag>> = dao.tags()

    override fun tagsOf(assetId: String): Flow<List<Tag>> = dao.tagsOf(assetId)

    override suspend fun addTag(assetIds: Collection<String>, name: String) {
        require(name.isNotBlank()) { "blank tag" }
        dao.addTag(assetIds, name.trim(), newId())
    }

    override suspend fun removeTag(assetId: String, tagId: String) = dao.untag(assetId, tagId)

    override fun albums(): Flow<List<Album>> = dao.albums()

    override suspend fun createAlbum(title: String): String {
        require(title.isNotBlank()) { "blank title" }
        return newId().also { dao.insertAlbum(it, title.trim()) }
    }

    override suspend fun addToAlbum(albumId: String, assetIds: List<String>) = dao.addToAlbum(albumId, assetIds)
}
