package com.ptzstudio.tapo

import android.net.Network
import com.ptzstudio.tapo.core.HttpReply
import com.ptzstudio.tapo.core.TapoHttp
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTPS para a câmera na rede local. As Tapo usam um certificado feito por elas mesmas (não dá para validar),
 * então ele é aceito só para o IP da câmera cadastrada; a senha nunca vai em texto aberto (o login é por desafio).
 */
class AndroidTapoHttp(
    private val host: String,
    private val network: () -> Network?,
    private val port: Int = 443,
    private val timeoutMs: Int = 6000,
) : TapoHttp {

    override fun post(path: String, body: ByteArray, headers: Map<String, String>): HttpReply {
        val url = URL("https", host, port, path)
        val net = network()
        val conn = (if (net != null) net.openConnection(url) else url.openConnection()) as HttpsURLConnection
        run {
            conn.sslSocketFactory = trustAll
            conn.hostnameVerifier = anyHost
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Host", "$host:$port")
            conn.setRequestProperty("Referer", "https://$host:$port")
            conn.setRequestProperty("Connection", "keep-alive")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
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

    companion object {
        private val anyHost = HostnameVerifier { _, _ -> true }

        private val trustAll: SSLSocketFactory by lazy {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }.socketFactory
        }
    }
}
