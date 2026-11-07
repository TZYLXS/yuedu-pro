package io.legado.app.help.http

import android.os.Build
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * API19 移植：Android 4.4 支持 TLSv1.2，但默认不在启用列表里，
 * OkHttp 3.x 不会自动打开，需要在这里显式启用。
 */
class Tls12SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

    @Throws(IOException::class)
    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
        val socket = delegate.createSocket(s, host, port, autoClose)
        return patch(socket)
    }

    @Throws(IOException::class)
    override fun createSocket(host: String?, port: Int): Socket {
        val socket = delegate.createSocket(host, port)
        return patch(socket)
    }

    @Throws(IOException::class)
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        val socket = delegate.createSocket(host, port, localHost, localPort)
        return patch(socket)
    }

    @Throws(IOException::class)
    override fun createSocket(host: InetAddress?, port: Int): Socket {
        val socket = delegate.createSocket(host, port)
        return patch(socket)
    }

    @Throws(IOException::class)
    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int
    ): Socket {
        val socket = delegate.createSocket(address, port, localAddress, localPort)
        return patch(socket)
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    private fun patch(socket: Socket): Socket {
        if (socket is SSLSocket) {
            return enableTls12(socket)
        }
        return socket
    }

    private fun enableTls12(socket: SSLSocket): SSLSocket {
        val supported = socket.supportedProtocols
        val enabled = socket.enabledProtocols
        if (supported.contains("TLSv1.2") && !enabled.contains("TLSv1.2")) {
            socket.enabledProtocols = enabled + "TLSv1.2"
        }
        return socket
    }

    companion object {
        fun wrap(factory: SSLSocketFactory): SSLSocketFactory {
            return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                Tls12SocketFactory(factory)
            } else {
                factory
            }
        }
    }
}
