package com.example.screenwatcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot

/** Kết quả phân loại một vật thể so với danh sách ảnh cần giữ. */
enum class ProtectionDecision { PROTECTED, UNPROTECTED, UNCERTAIN }

data class ProtectedTemplate(
    val id: String,
    val name: String,
    val fileName: String,
    val threshold: Float = 0.85f,
)

/** Lưu ảnh mẫu trong bộ nhớ riêng của ứng dụng, không phụ thuộc quyền truy cập thư viện về sau. */
object InventoryProtectorStore {
    private const val PREF = "inventory_protector"
    private const val KEY_ITEMS = "items"
    private const val MAX_TEMPLATE_BYTES = 8 * 1024 * 1024L

    private fun dir(c: Context): File = File(c.filesDir, "inventory_protector").apply { mkdirs() }

    @Synchronized
    fun all(context: Context): List<ProtectedTemplate> {
        val c = context.applicationContext
        val raw = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_ITEMS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val item = ProtectedTemplate(
                        id = o.optString("id"),
                        name = o.optString("name", "Vật phẩm"),
                        fileName = o.optString("fileName"),
                        threshold = o.optDouble("threshold", 0.85).toFloat().coerceIn(0.85f, 0.99f),
                    )
                    if (item.id.isNotBlank() && File(dir(c), item.fileName).isFile) add(item)
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun count(context: Context): Int = all(context).size

    @Synchronized
    fun add(context: Context, uri: Uri, name: String): String? {
        val c = context.applicationContext
        val decoded = try {
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) { null } ?: return "Không đọc được ảnh mẫu. Hãy chọn ảnh PNG hoặc JPG hợp lệ."
        try {
            if (decoded.width < 8 || decoded.height < 8) return "Ảnh mẫu quá nhỏ; hãy chọn ảnh rõ hơn (ít nhất 8×8 pixel)."
            val id = "protect_${System.currentTimeMillis()}_${(1000..9999).random()}"
            val fileName = "$id.png"
            val target = File(dir(c), fileName)
            FileOutputStream(target).use { out ->
                if (!decoded.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    target.delete()
                    return "Không lưu được ảnh mẫu."
                }
            }
            if (target.length() > MAX_TEMPLATE_BYTES) {
                target.delete()
                return "Ảnh mẫu lớn hơn 8 MB. Hãy cắt gọn vật phẩm rồi thêm lại."
            }
            val items = all(c).toMutableList()
            items.add(ProtectedTemplate(id, name.ifBlank { "Vật phẩm ${items.size + 1}" }, fileName))
            save(c, items)
            return null
        } catch (e: Exception) {
            return "Lỗi lưu ảnh mẫu: ${e.message ?: "không xác định"}"
        } finally {
            decoded.recycle()
        }
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val c = context.applicationContext
        val old = all(c)
        val target = old.firstOrNull { it.id == id }
        if (target != null) File(dir(c), target.fileName).delete()
        save(c, old.filterNot { it.id == id })
    }

    private fun save(c: Context, items: List<ProtectedTemplate>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().put("id", item.id).put("name", item.name)
                .put("fileName", item.fileName).put("threshold", item.threshold.toDouble()))
        }
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun loadBitmap(context: Context, item: ProtectedTemplate): Bitmap? =
        BitmapFactory.decodeFile(File(dir(context.applicationContext), item.fileName).absolutePath)
}

/** So sánh crop vật thể với tất cả ảnh bảo vệ. Mọi trường hợp đầu vào không đáng tin đều fail-safe. */
object InventoryProtector {
    fun crop(source: Bitmap, box: Rect): Bitmap? {
        if (source.isRecycled || source.width < 1 || source.height < 1) return null
        val safe = Rect(box).apply {
            left = left.coerceIn(0, source.width)
            top = top.coerceIn(0, source.height)
            right = right.coerceIn(0, source.width)
            bottom = bottom.coerceIn(0, source.height)
        }
        if (safe.width() < 4 || safe.height() < 4) return null
        return try { Bitmap.createBitmap(source, safe.left, safe.top, safe.width(), safe.height()) }
        catch (_: Exception) { null }
    }

    fun classify(context: Context, itemBitmap: Bitmap?, detectorScore: Float, requiredScore: Double): ProtectionDecision {
        if (itemBitmap == null || itemBitmap.isRecycled || itemBitmap.width < 4 || itemBitmap.height < 4)
            return ProtectionDecision.UNCERTAIN
        if (!detectorScore.isFinite() || detectorScore.toDouble() < requiredScore || detectorScore < 0.85f)
            return ProtectionDecision.UNCERTAIN
        val templates = InventoryProtectorStore.all(context)
        if (templates.isEmpty()) return ProtectionDecision.UNPROTECTED
        var uncertain = false
        for (template in templates) {
            val sample = InventoryProtectorStore.loadBitmap(context, template)
            if (sample == null) {
                uncertain = true
                continue
            }
            try {
                val hit = TemplateMatcher.find(itemBitmap, sample, template.threshold.toDouble())
                if (hit != null && hit.score >= template.threshold) return ProtectionDecision.PROTECTED
            } catch (_: Exception) {
                uncertain = true
            } finally {
                sample.recycle()
            }
        }
        // Nếu mọi phép so sánh chạy bình thường và không trùng mẫu, cho phép chuyển qua JOB.
        return if (uncertain) ProtectionDecision.UNCERTAIN else ProtectionDecision.UNPROTECTED
    }
}

/** Chống phát JOB lặp cho cùng vị trí vật thể. Vật thể được xem là biến mất sau 2 lượt quét vắng mặt. */
object InventoryTracker {
    private data class Track(var x: Float, var y: Float, var missed: Int = 0)
    private val tracks = HashMap<String, MutableList<Track>>()
    private const val MATCH_DISTANCE = 0.025f
    private const val REMOVE_AFTER_MISSES = 2

    @Synchronized
    fun onlyNew(ruleId: String, points: List<IntArray>, frameWidth: Int, frameHeight: Int): List<IntArray> {
        if (frameWidth <= 0 || frameHeight <= 0) return emptyList()
        val current = tracks.getOrPut(ruleId) { mutableListOf() }
        val used = HashSet<Int>()
        val fresh = ArrayList<IntArray>()
        val diagonal = hypot(frameWidth.toDouble(), frameHeight.toDouble()).toFloat().coerceAtLeast(1f)
        for (p in points) {
            if (p.size < 2) continue
            val nx = p[0].toFloat() / frameWidth
            val ny = p[1].toFloat() / frameHeight
            var bestIndex = -1
            var bestDistance = Float.MAX_VALUE
            current.forEachIndexed { index, track ->
                if (index in used) return@forEachIndexed
                val dx = (track.x - nx) * frameWidth
                val dy = (track.y - ny) * frameHeight
                val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat() / diagonal
                if (distance < bestDistance) { bestDistance = distance; bestIndex = index }
            }
            if (bestIndex >= 0 && bestDistance <= MATCH_DISTANCE) {
                val track = current[bestIndex]
                track.x = nx; track.y = ny; track.missed = 0
                used.add(bestIndex)
            } else {
                fresh.add(p)
                current.add(Track(nx, ny))
                used.add(current.lastIndex)
            }
        }
        current.forEachIndexed { index, track -> if (index !in used) track.missed++ }
        current.removeAll { it.missed >= REMOVE_AFTER_MISSES }
        return fresh
    }

    @Synchronized
    fun reset(ruleId: String? = null) {
        if (ruleId == null) tracks.clear() else tracks.remove(ruleId)
    }
}
