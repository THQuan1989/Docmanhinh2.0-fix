package com.example.screenwatcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * Nhận diện vật thể bằng mô hình AI chạy offline trên máy (MediaPipe Object Detector).
 * Mô hình là file .tflite do bạn tự huấn luyện (MediaPipe Model Maker, định dạng COCO) rồi nạp vào app;
 * chỉ dùng 1 mô hình chung, mỗi luật chọn loại vật thể (nhãn) cần tìm.
 */
object AiDetector {
    class Det(val label: String, val score: Float, val box: Rect)

    private const val MODEL = "ai_model.tflite"
    private var det: ObjectDetector? = null
    private var key = ""

    fun modelFile(c: Context) = File(c.filesDir, MODEL)
    fun hasModel(c: Context): Boolean = modelFile(c).let { it.exists() && it.length() > 0 }

    fun modelInfo(c: Context): String =
        if (hasModel(c)) "🤖 Đã nạp mô hình AI (%.1f MB)".format(modelFile(c).length() / 1048576.0)
        else "🤖 Chưa nạp mô hình AI (.tflite)"

    /** Chép mô hình từ [uri] vào app và thử nạp. Trả null nếu ổn, ngược lại trả thông báo lỗi. */
    fun importModel(c: Context, uri: Uri): String? {
        val tmp = File(c.filesDir, "ai_model.tmp")
        try {
            val input = c.contentResolver.openInputStream(uri) ?: return "Không đọc được file"
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (tmp.length() < 1024) { tmp.delete(); return "File quá nhỏ, không phải mô hình" }
            val old = modelFile(c)
            val backup = File(c.filesDir, "ai_model.old")
            if (old.exists()) old.renameTo(backup)
            tmp.renameTo(old)
            synchronized(this) { det?.close(); det = null; key = "" }
            try {
                synchronized(this) { ensure(c) }  // thử nạp ngay để báo lỗi sớm
            } catch (e: Throwable) {
                old.delete()
                if (backup.exists()) backup.renameTo(old)
                synchronized(this) { det?.close(); det = null; key = "" }
                return "Mô hình không hợp lệ: ${e.message}"
            }
            backup.delete()
            ProfileBackup.async { ProfileBackup.exportModel(c) }
            return null
        } catch (e: Throwable) {
            tmp.delete()
            return "Lỗi nạp mô hình: ${e.message}"
        }
    }

    private fun ensure(c: Context): ObjectDetector {
        val f = modelFile(c)
        if (!hasModel(c)) throw IllegalStateException("Chưa nạp mô hình AI (.tflite)")
        val k = "${f.length()}|${f.lastModified()}"
        det?.let { if (k == key) return it }
        det?.close()
        det = null
        val buf = FileInputStream(f).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()) }
        val opts = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buf).build())
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(0.05f) // lấy thật thấp rồi lọc theo ngưỡng của từng luật (và để báo cho người dùng biết AI thấy gì)
            .setMaxResults(100)
            .build()
        val d = ObjectDetector.createFromOptions(c.applicationContext, opts)
        det = d
        key = k
        return d
    }

    /** Tìm vật thể trong [bmp]; toạ độ khung theo pixel của [bmp]. Ném lỗi nếu chưa có mô hình. */
    @Synchronized
    fun detect(c: Context, bmp: Bitmap): List<Det> {
        val d = ensure(c)
        val res = d.detect(BitmapImageBuilder(bmp).build())
        val out = ArrayList<Det>()
        for (dt in res.detections()) {
            val cat = dt.categories().firstOrNull() ?: continue
            val b = dt.boundingBox()
            out.add(Det(cat.categoryName() ?: "", cat.score(), Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())))
        }
        return out
    }
}
