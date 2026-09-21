package cn.dsr213.nasphoto.net

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * 全 App 共享的「宽松 TLS」工厂。
 *
 * NAS 的 HTTPS 证书由小米 IoT 网关注发（自签、约 21 天轮换），**手机侧系统信任链不认它**，
 * 固定指纹也必然因轮换而失效。所以策略统一为：先尝试严格校验，失败才落到这里。
 *
 * 为什么单独抽出来 —— **通道探测**和**真实传输**必须用同一个工厂。
 * 两边各写一份的话，迟早出现「探测说通、真传时又握手失败」的错配，
 * 而这类问题从日志上极难看出来（两边都"成功"过）。
 */
object Tls {

    @Volatile
    private var cached: SSLSocketFactory? = null

    fun permissiveFactory(): SSLSocketFactory {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), SecureRandom())
            return ctx.socketFactory.also { cached = it }
        }
    }
}
