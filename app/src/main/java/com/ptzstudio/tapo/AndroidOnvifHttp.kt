package com.ptzstudio.tapo

import android.net.Network
import com.ptzstudio.tapo.core.HttpReply
import com.ptzstudio.tapo.core.OnvifHttp
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** HTTP para o ONVIF da câmera (porta 2020). A senha vai cifrada no próprio pedido (WS-Security), não em texto. */
class AndroidOnvifHttp(private val network: () -> Network?, private val timeoutMs: Int = 5000) : OnvifHttp {
    override fun post(url: String, body: ByteArray, contentType: String): HttpReply {
        val u = URL(url)
        val net = network()
        val conn = (if (net != null) net.openConnection(u) else u.openConnection()) as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.useCaches = false
        conn.setRequestProperty("Content-Type", contentType)
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val data = stream?.use { s ->
            val out = ByteArrayOutputStream()
            s.copyTo(out)
            out.toByteArray()
        } ?: ByteArray(0)
        return HttpReply(status, data)
    }
}
