package com.example.screenwatcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Gán nhãn ảnh ngay trên điện thoại để huấn luyện AI nhận diện vật thể:
 * kéo khung quanh vật thể, chọn tên loại, rồi xuất bộ dữ liệu COCO (file zip) cho MediaPipe Model Maker.
 * Ảnh lấy từ mục "📸 Chụp ảnh" ở màn hình chính hoặc "➕ Thêm ảnh từ máy".
 */
class LabelActivity : Activity() {
    private class Box(var cls: Int, var l: Int, var t: Int, var r: Int, var b: Int)
    private class Img(val file: String, val w: Int, val h: Int, val boxes: MutableList<Box> = mutableListOf())

    private val classes = ArrayList<String>()
    private val imgs = ArrayList<Img>()
    private var idx = 0
    private var cur = 0 // loại đang dùng cho khung mới

    private lateinit var boxView: BoxView
    private lateinit var info: TextView
    private lateinit var chips: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun imgDir() = File(filesDir, "dataset").apply { mkdirs() }
    private fun stateFile() = File(filesDir, "labels_state.json")

    /* ---------------- lưu / nạp ---------------- */

    private fun save() {
        runCatching {
            val o = JSONObject()
            o.put("classes", JSONArray(classes))
            o.put("images", JSONArray().apply {
                for (im in imgs) put(
                    JSONObject().put("file", im.file).put("w", im.w).put("h", im.h).put(
                        "boxes",
                        JSONArray().apply { for (b in im.boxes) put(JSONArray().put(b.cls).put(b.l).put(b.t).put(b.r).put(b.b)) },
                    ),
                )
            })
            stateFile().writeText(o.toString())
        }
    }

    private fun load() {
        classes.clear(); imgs.clear()
        runCatching {
            val o = JSONObject(stateFile().readText())
            val cs = o.getJSONArray("classes")
            for (i in 0 until cs.length()) classes.add(cs.getString(i))
            val arr = o.getJSONArray("images")
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                if (!File(imgDir(), j.getString("file")).exists()) continue
                val im = Img(j.getString("file"), j.getInt("w"), j.getInt("h"))
                val bs = j.getJSONArray("boxes")
                for (k in 0 until bs.length()) {
                    val b = bs.getJSONArray(k)
                    im.boxes.add(Box(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3), b.getInt(4)))
                }
                imgs.add(im)
            }
        }
        // ảnh mới trong thư mục (vừa chụp) mà chưa có trong danh sách
        val known = imgs.map { it.file }.toSet()
        for (f in (imgDir().listFiles() ?: emptyArray()).sortedBy { it.name }) {
            if (f.name in known || !f.name.lowercase().endsWith(".jpg")) continue
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth > 0) imgs.add(Img(f.name, o.outWidth, o.outHeight))
        }
        if (cur >= classes.size) cur = 0
        if (idx >= imgs.size) idx = (imgs.size - 1).coerceAtLeast(0)
    }

    /* ---------------- giao diện ---------------- */

    private fun btn(t: String, w: Float = 1f, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 12f; maxLines = 1
        minWidth = 0; minimumWidth = 0; setPadding(dp(2), 0, dp(2), 0)
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        load()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            fitsSystemWindows = true
        }
        info = TextView(this).apply { setTextColor(Color.BLACK); textSize = 14f }
        root.addView(info)
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { addView(chips) })
        boxView = BoxView()
        root.addView(boxView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(btn("◀ Trước") { go(-1) })
        nav.addView(btn("Sau ▶") { go(1) })
        nav.addView(btn("🗑 Xoá khung") { deleteBox() }) // khung đang chọn
        nav.addView(btn("🗑 Xoá ảnh") { deleteImage() })
        root.addView(nav)
        val ops = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ops.addView(btn("➕ Thêm ảnh từ máy", 1.2f) { pickImages() })
        ops.addView(btn("📤 Xuất COCO (zip)", 1.2f) { exportZip() })
        root.addView(ops)
        root.addView(TextView(this).apply {
            textSize = 11f; setTextColor(Color.DKGRAY)
            text = "Kéo trên ảnh để vẽ khung quanh vật thể (khung mới lấy loại đang chọn). Chạm vào khung để chọn; " +
                "chọn khung rồi bấm tên loại để đổi loại của khung đó."
        })
        setContentView(root)
        refreshChips()
        showCurrent()
    }

    private fun refreshChips() {
        chips.removeAllViews()
        for ((i, n) in classes.withIndex()) {
            chips.addView(Button(this).apply {
                text = (if (i == cur) "● " else "") + n
                isAllCaps = false; textSize = 12f; minWidth = 0; minimumWidth = 0
                setOnClickListener {
                    cur = i
                    boxView.selectedBox()?.let { it.cls = i; save() }
                    refreshChips(); boxView.invalidate(); updateInfo()
                }
            })
        }
        chips.addView(Button(this).apply {
            text = "＋ Loại mới"; isAllCaps = false; textSize = 12f
            setOnClickListener { newClassDialog() }
        })
    }

    private fun newClassDialog() {
        val et = EditText(this).apply { hint = "vd: nut_nhan, quai_vat"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Tên loại vật thể").setView(et)
            .setPositiveButton("Thêm") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty() && n !in classes) {
                    classes.add(n); cur = classes.size - 1
                    boxView.selectedBox()?.let { it.cls = cur }
                    save(); refreshChips(); boxView.invalidate(); updateInfo()
                }
            }.setNegativeButton("Huỷ", null).show()
    }

    private fun updateInfo() {
        val total = imgs.sumOf { it.boxes.size }
        info.text = if (imgs.isEmpty()) "Chưa có ảnh. Chụp ở mục AI (màn hình chính) hoặc bấm ➕ Thêm ảnh từ máy."
        else "Ảnh ${idx + 1}/${imgs.size} • ${imgs[idx].boxes.size} khung (tổng $total) • loại: ${classes.getOrNull(cur) ?: "(chưa có)"}"
    }

    private fun go(d: Int) {
        if (imgs.isEmpty()) return
        idx = (idx + d).coerceIn(0, imgs.size - 1)
        save(); showCurrent()
    }

    private fun showCurrent() {
        if (imgs.isEmpty()) { boxView.set(null, 0, 0, null); updateInfo(); return }
        val im = imgs[idx]
        val o = BitmapFactory.Options().apply {
            var s = 1
            while (im.w / s > 2000 || im.h / s > 2000) s *= 2
            inSampleSize = s
        }
        val bmp = BitmapFactory.decodeFile(File(imgDir(), im.file).path, o)
        boxView.set(bmp, im.w, im.h, im.boxes)
        updateInfo()
    }

    private fun deleteBox() {
        if (boxView.removeSelected()) { save(); updateInfo() }
        else Toast.makeText(this, "Chạm vào một khung để chọn trước", Toast.LENGTH_SHORT).show()
    }

    private fun deleteImage() {
        if (imgs.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Xoá ảnh này?").setMessage(imgs[idx].file)
            .setPositiveButton("Xoá") { _, _ ->
                File(imgDir(), imgs[idx].file).delete()
                imgs.removeAt(idx)
                if (idx >= imgs.size) idx = (imgs.size - 1).coerceAtLeast(0)
                save(); showCurrent()
            }.setNegativeButton("Huỷ", null).show()
    }

    /* ---------------- thêm ảnh từ máy ---------------- */

    private fun pickImages() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            REQ_IMPORT,
        )
    }

    private fun exportZip() {
        val usable = imgs.filter { it.boxes.isNotEmpty() }
        if (usable.isEmpty()) {
            Toast.makeText(this, "Chưa có ảnh nào được khoanh khung", Toast.LENGTH_LONG).show(); return
        }
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "dataset_coco.zip"),
            REQ_EXPORT,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_IMPORT -> {
                val uris = ArrayList<android.net.Uri>()
                data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
                data.data?.let { uris.add(it) }
                Thread {
                    var n = 0
                    for ((k, u) in uris.withIndex()) runCatching {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
                        var s = 1
                        while (o.outWidth / s > 2400 || o.outHeight / s > 2400) s *= 2
                        val bmp = contentResolver.openInputStream(u)?.use {
                            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
                        } ?: return@runCatching
                        val name = "imp_${System.currentTimeMillis()}_$k.jpg"
                        File(imgDir(), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                        imgs.add(Img(name, bmp.width, bmp.height))
                        bmp.recycle()
                        n++
                        Unit
                    }
                    runOnUiThread {
                        save(); showCurrent()
                        Toast.makeText(this@LabelActivity, "Đã thêm $n ảnh", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            REQ_EXPORT -> {
                val uri = data.data ?: return
                val snapshot = imgs.filter { it.boxes.isNotEmpty() }.map { im ->
                    Img(im.file, im.w, im.h, im.boxes.map { Box(it.cls, it.l, it.t, it.r, it.b) }.toMutableList())
                }
                val names = ArrayList(classes)
                Thread {
                    val msg: String = exportTo(uri, snapshot, names)
                    runOnUiThread { Toast.makeText(this@LabelActivity, msg, Toast.LENGTH_LONG).show() }
                }.start()
            }
        }
    }

    private fun exportTo(uri: android.net.Uri, snapshot: List<Img>, names: List<String>): String {
        return try {
            val os = contentResolver.openOutputStream(uri, "w")
            if (os == null) "Không ghi được file" else writeCoco(os, snapshot, names)
        } catch (e: Exception) {
            "Lỗi xuất: ${e.message}"
        }
    }

    /**
     * Ghi bộ dữ liệu COCO dạng zip đúng cấu trúc MediaPipe Model Maker đọc:
     *   thư mục train gồm images (các ảnh .jpg) và labels.json; thư mục validation cũng vậy
     * Chỉ dùng các loại có khung; id loại bắt đầu từ 1, id 0 là "background".
     */
    private fun writeCoco(out: OutputStream, labeled: List<Img>, names: List<String>): String {
        val used = labeled.flatMap { it.boxes.map { b -> b.cls } }.toSortedSet().toList()
        val newId = used.withIndex().associate { (i, c) -> c to i + 1 }
        var valid = labeled.filterIndexed { i, _ -> i % 5 == 4 }
        var train = labeled.filterIndexed { i, _ -> i % 5 != 4 }
        if (valid.isEmpty()) { // ít ảnh: lấy ảnh cuối làm kiểm tra; chỉ 1 ảnh thì dùng chung
            if (labeled.size >= 2) { valid = listOf(labeled.last()); train = labeled.dropLast(1) }
            else { valid = labeled; train = labeled }
        }
        ZipOutputStream(out).use { z ->
            for ((split, list) in listOf("train" to train, "validation" to valid)) {
                val images = JSONArray()
                val anns = JSONArray()
                var annId = 1
                for ((n, im) in list.withIndex()) {
                    z.putNextEntry(ZipEntry("$split/images/${im.file}"))
                    z.write(File(imgDir(), im.file).readBytes())
                    z.closeEntry()
                    images.put(JSONObject().put("id", n + 1).put("file_name", im.file).put("width", im.w).put("height", im.h))
                    for (b in im.boxes) {
                        val w = b.r - b.l
                        val h = b.b - b.t
                        anns.put(
                            JSONObject().put("id", annId++).put("image_id", n + 1).put("category_id", newId[b.cls] ?: 1)
                                .put("bbox", JSONArray().put(b.l).put(b.t).put(w).put(h)).put("area", w * h).put("iscrowd", 0),
                        )
                    }
                }
                val cats = JSONArray().put(JSONObject().put("id", 0).put("name", "background"))
                for (c in used) cats.put(JSONObject().put("id", newId[c]).put("name", names.getOrElse(c) { "loai_$c" }))
                z.putNextEntry(ZipEntry("$split/labels.json"))
                z.write(JSONObject().put("images", images).put("annotations", anns).put("categories", cats).toString().toByteArray())
                z.closeEntry()
            }
        }
        return "Đã xuất ${labeled.size} ảnh, ${used.size} loại (train ${train.size}, kiểm tra ${valid.size})"
    }

    /* ---------------- vùng vẽ khung ---------------- */

    private inner class BoxView : View(this@LabelActivity) {
        private var bmp: Bitmap? = null
        private var ow = 0
        private var oh = 0
        private var boxes: MutableList<Box> = mutableListOf()
        private var sel = -1
        private var sx = 0f; private var sy = 0f
        private var cx = 0f; private var cy = 0f
        private var drawing = false
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val txt = Paint().apply { textSize = dp(13).toFloat(); isAntiAlias = true }
        private val fill = Paint().apply { style = Paint.Style.FILL }

        init { setBackgroundColor(Color.parseColor("#222222")) }

        fun set(b: Bitmap?, w: Int, h: Int, list: MutableList<Box>?) {
            bmp?.recycle(); bmp = b; ow = w; oh = h
            boxes = list ?: mutableListOf(); sel = -1; drawing = false
            invalidate()
        }

        fun selectedBox(): Box? = boxes.getOrNull(sel)
        fun removeSelected(): Boolean {
            if (sel !in boxes.indices) return false
            boxes.removeAt(sel); sel = -1; invalidate(); return true
        }

        private fun scale() = if (ow > 0 && oh > 0) minOf(width.toFloat() / ow, height.toFloat() / oh) else 1f
        private fun offX() = (width - ow * scale()) / 2
        private fun offY() = (height - oh * scale()) / 2
        private fun toImgX(x: Float) = ((x - offX()) / scale()).coerceIn(0f, ow.toFloat())
        private fun toImgY(y: Float) = ((y - offY()) / scale()).coerceIn(0f, oh.toFloat())
        private fun color(c: Int) = Color.HSVToColor(floatArrayOf(((c * 67) % 360).toFloat(), 0.8f, 1f))

        override fun onDraw(c: Canvas) {
            val b = bmp ?: return
            val s = scale(); val ox = offX(); val oy = offY()
            c.drawBitmap(b, Rect(0, 0, b.width, b.height), RectF(ox, oy, ox + ow * s, oy + oh * s), paint)
            for ((i, bx) in boxes.withIndex()) {
                val r = RectF(ox + bx.l * s, oy + bx.t * s, ox + bx.r * s, oy + bx.b * s)
                line.color = if (i == sel) Color.WHITE else color(bx.cls)
                line.strokeWidth = dp(if (i == sel) 3 else 2).toFloat()
                c.drawRect(r, line)
                val name = classes.getOrElse(bx.cls) { "?" }
                fill.color = color(bx.cls)
                val tw = txt.measureText(name) + dp(6)
                c.drawRect(r.left, r.top - dp(18), r.left + tw, r.top, fill)
                txt.color = Color.BLACK
                c.drawText(name, r.left + dp(3), r.top - dp(5), txt)
            }
            if (drawing) {
                line.color = color(cur); line.strokeWidth = dp(2).toFloat()
                c.drawRect(minOf(sx, cx), minOf(sy, cy), maxOf(sx, cx), maxOf(sy, cy), line)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (bmp == null) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; cx = e.x; cy = e.y; drawing = false }
                MotionEvent.ACTION_MOVE -> {
                    cx = e.x; cy = e.y
                    if (!drawing && Math.hypot((cx - sx).toDouble(), (cy - sy).toDouble()) > dp(8)) drawing = true
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (drawing) {
                        drawing = false
                        val l = toImgX(minOf(sx, cx)).toInt(); val r = toImgX(maxOf(sx, cx)).toInt()
                        val t = toImgY(minOf(sy, cy)).toInt(); val bt = toImgY(maxOf(sy, cy)).toInt()
                        if (r - l >= 8 && bt - t >= 8) addBox(Box(cur, l, t, r, bt))
                    } else {
                        val px = toImgX(e.x); val py = toImgY(e.y)
                        sel = boxes.withIndex().filter { (_, b) -> px >= b.l && px <= b.r && py >= b.t && py <= b.b }
                            .minByOrNull { (_, b) -> (b.r - b.l).toLong() * (b.b - b.t) }?.index ?: -1
                        selectedBoxClassToCurrent()
                    }
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { drawing = false; invalidate() }
            }
            return true
        }

        private fun addBox(b: Box) {
            if (classes.isEmpty()) { // chưa có loại nào: tạo loại mặc định
                classes.add("vat_the"); cur = 0; b.cls = 0
                refreshChips()
            }
            boxes.add(b); sel = boxes.size - 1
            save(); updateInfo()
        }

        /** Chọn một khung thì cũng chuyển "loại đang dùng" sang loại của khung đó. */
        private fun selectedBoxClassToCurrent() {
            selectedBox()?.let { if (it.cls != cur) { cur = it.cls; refreshChips() } }
            updateInfo()
        }
    }

    companion object {
        private const val REQ_IMPORT = 301
        private const val REQ_EXPORT = 302
    }
}
