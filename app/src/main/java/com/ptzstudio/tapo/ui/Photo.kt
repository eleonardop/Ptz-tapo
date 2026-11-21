package com.ptzstudio.tapo.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tira a foto do quadro que está no vídeo ao vivo (na resolução do vídeo) e guarda na galeria,
 * na pasta Imagens/PTZ Tapo.
 */
object PhotoTaker {
    /** Vídeo que está na tela agora (registrado pelo [RtspVideo]). */
    @Volatile var view: PlayerView? = null

    fun capture(context: Context, cameraName: String, done: (String) -> Unit) {
        val v = view
        val surface = v?.videoSurfaceView as? SurfaceView
        val size = v?.player?.videoSize
        if (v == null || surface == null || surface.holder.surface?.isValid != true) {
            done("Sem vídeo na tela: ligue o vídeo ao vivo para tirar a foto")
            return
        }
        val w = size?.width?.takeIf { it > 0 } ?: surface.width
        val h = size?.height?.takeIf { it > 0 } ?: surface.height
        if (w <= 0 || h <= 0) {
            done("O vídeo ainda não começou")
            return
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surface, bmp, { result ->
                if (result != PixelCopy.SUCCESS) {
                    done("Não deu para copiar a imagem do vídeo")
                    return@request
                }
                Thread {
                    val msg = try {
                        save(context, bmp, cameraName)
                        "Foto salva na galeria (Imagens/PTZ Tapo)"
                    } catch (e: Exception) {
                        "Não deu para salvar a foto: ${e.message}"
                    }
                    Handler(Looper.getMainLooper()).post { done(msg) }
                }.start()
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            done("Não deu para tirar a foto: ${e.message}")
        }
    }

    private fun save(context: Context, bmp: Bitmap, cameraName: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val safe = cameraName.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { "camera" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${safe}_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PTZ Tapo")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("galeria indisponível")
        try {
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: throw IllegalStateException("sem acesso")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
