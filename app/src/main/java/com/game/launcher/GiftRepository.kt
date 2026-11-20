package com.game.launcher

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.giftDataStore by preferencesDataStore(name = "gift_store")
private val KEY_GIFTS = stringPreferencesKey("gifts_json")

/**
 * Repository đọc/ghi danh sách [Gift] vào DataStore Preferences dưới dạng JSON.
 * Dùng org.json (có sẵn trong Android), không thêm thư viện.
 * JSON hỏng → trả về danh sách rỗng, không crash.
 * Mỗi thao tác là MỘT lần edit nguyên tử.
 */
class GiftRepository(private val context: Context) {

    val giftsFlow: Flow<List<Gift>> = context.giftDataStore.data.map { prefs ->
        parseGifts(prefs[KEY_GIFTS])
    }

    /** Đọc snapshot một lần (suspend). */
    suspend fun readGiftsOnce(): List<Gift> = withContext(Dispatchers.IO) {
        val prefs = context.giftDataStore.data.first()
        parseGifts(prefs[KEY_GIFTS])
    }

    suspend fun saveGifts(gifts: List<Gift>) = withContext(Dispatchers.IO) {
        context.giftDataStore.edit { prefs ->
            prefs[KEY_GIFTS] = serializeGifts(gifts)
        }
    }

    /** Thêm hoặc cập nhật một note (theo id). Nguyên tử. */
    suspend fun upsertGift(gift: Gift) = withContext(Dispatchers.IO) {
        context.giftDataStore.edit { prefs ->
            val current = parseGifts(prefs[KEY_GIFTS]).toMutableList()
            val idx = current.indexOfFirst { it.id == gift.id }
            if (idx >= 0) current[idx] = gift else current.add(gift)
            prefs[KEY_GIFTS] = serializeGifts(current)
        }
    }

    /** Xóa một note theo id. Nguyên tử. */
    suspend fun deleteGift(id: String) = withContext(Dispatchers.IO) {
        context.giftDataStore.edit { prefs ->
            val current = parseGifts(prefs[KEY_GIFTS]).toMutableList()
            current.removeAll { it.id == id }
            prefs[KEY_GIFTS] = serializeGifts(current)
        }
    }

    /** Xóa tất cả note của một game và trả về danh sách các id đã xóa. Nguyên tử. */
    suspend fun deleteGiftsForPackage(gamePackage: String): List<String> {
        var deletedIds: List<String> = emptyList()
        withContext(Dispatchers.IO) {
            context.giftDataStore.edit { prefs ->
                val current = parseGifts(prefs[KEY_GIFTS]).toMutableList()
                deletedIds = current.filter { it.gamePackage == gamePackage }.map { it.id }
                current.removeAll { it.gamePackage == gamePackage }
                prefs[KEY_GIFTS] = serializeGifts(current)
            }
        }
        return deletedIds
    }

    companion object {
        fun parseGifts(json: String?): List<Gift> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                val list = mutableListOf<Gift>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val claimedAt = obj.optLong("claimedAt", -1L).takeIf { it >= 0 }
                    val lastQuickOffset = obj.optLong("lastQuickOffsetMillis", -1L).takeIf { it >= 0 }
                    val notifiedFor = obj.optLong("notifiedForMillis", -1L).takeIf { it >= 0 }
                    list.add(
                        Gift(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            gamePackage = obj.optString("gamePackage", ""),
                            note = obj.optString("note", ""),
                            availableAtMillis = obj.optLong("availableAtMillis", 0L),
                            isClaimed = obj.optBoolean("isClaimed", false),
                            claimedAt = claimedAt,
                            lastQuickOffsetMillis = lastQuickOffset,
                            notifiedForMillis = notifiedFor,
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
                list.filter { it.gamePackage.isNotEmpty() && it.availableAtMillis > 0L }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun serializeGifts(gifts: List<Gift>): String {
            val arr = JSONArray()
            for (g in gifts) {
                val obj = JSONObject()
                obj.put("id", g.id)
                obj.put("gamePackage", g.gamePackage)
                obj.put("note", g.note)
                obj.put("availableAtMillis", g.availableAtMillis)
                obj.put("isClaimed", g.isClaimed)
                if (g.claimedAt != null) obj.put("claimedAt", g.claimedAt)
                if (g.lastQuickOffsetMillis != null) obj.put("lastQuickOffsetMillis", g.lastQuickOffsetMillis)
                if (g.notifiedForMillis != null) obj.put("notifiedForMillis", g.notifiedForMillis)
                obj.put("createdAt", g.createdAt)
                arr.put(obj)
            }
            return arr.toString()
        }
    }
}
