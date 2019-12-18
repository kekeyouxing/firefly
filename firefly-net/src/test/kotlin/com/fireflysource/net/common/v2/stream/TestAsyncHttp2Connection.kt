package com.fireflysource.net.common.v2.stream

import com.fireflysource.common.lifecycle.AbstractLifeCycle.stopAll
import com.fireflysource.common.sys.Result.discard
import com.fireflysource.common.sys.Result.futureToConsumer
import com.fireflysource.net.http.client.impl.Http2ClientConnection
import com.fireflysource.net.http.common.HttpConfig
import com.fireflysource.net.http.common.v2.frame.ErrorCode
import com.fireflysource.net.http.common.v2.frame.GoAwayFrame
import com.fireflysource.net.http.common.v2.frame.PingFrame
import com.fireflysource.net.http.common.v2.frame.SettingsFrame
import com.fireflysource.net.http.common.v2.stream.Http2Connection
import com.fireflysource.net.http.common.v2.stream.SimpleFlowControlStrategy
import com.fireflysource.net.http.server.impl.Http2ServerConnection
import com.fireflysource.net.tcp.aio.AioTcpClient
import com.fireflysource.net.tcp.aio.AioTcpServer
import com.fireflysource.net.tcp.aio.TcpConfig
import com.fireflysource.net.tcp.onAcceptAsync
import com.fireflysource.net.tcp.startReadingAndAwaitHandshake
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

class TestAsyncHttp2Connection {

    @Test
    fun testGoAway() = runBlocking {
        val host = "localhost"
        val port = 4022
        val tcpConfig = TcpConfig(30, false)
        val httpConfig = HttpConfig()

        AioTcpServer(tcpConfig).onAcceptAsync { connection ->
            connection.startReadingAndAwaitHandshake()
            Http2ServerConnection(
                httpConfig, connection, SimpleFlowControlStrategy(),
                object : Http2Connection.Listener.Adapter() {

                    override fun onClose(http2Connection: Http2Connection, frame: GoAwayFrame) {
                        println("server receives go away frame: $frame")
                    }
                }
            )
        }.listen(host, port)

        val client = AioTcpClient(tcpConfig)
        val connection = client.connect(host, port).await()
        connection.startReadingAndAwaitHandshake()
        val http2Connection = Http2ClientConnection(
            httpConfig, connection, SimpleFlowControlStrategy(),
            object : Http2Connection.Listener.Adapter() {

                override fun onClose(http2Connection: Http2Connection, frame: GoAwayFrame) {
                    println("client receives go away frame: $frame")
                }
            }
        )

        val future = CompletableFuture<Void>()
        val success = http2Connection.close(
            ErrorCode.INTERNAL_ERROR.code, "test error message",
            futureToConsumer(future)
        )
        assertTrue(success)
        future.await()

        stopTest(http2Connection)
    }

    @Test
    fun testSettings() = runBlocking {
        val host = "localhost"
        val port = 4021
        val channel = Channel<SettingsFrame>()
        val tcpConfig = TcpConfig(30, false)
        val httpConfig = HttpConfig()

        val settingsFrame = SettingsFrame(
            mutableMapOf(
                SettingsFrame.HEADER_TABLE_SIZE to 8192,
                SettingsFrame.ENABLE_PUSH to 1,
                SettingsFrame.MAX_CONCURRENT_STREAMS to 300,
                SettingsFrame.INITIAL_WINDOW_SIZE to 128 * 1024,
                SettingsFrame.MAX_FRAME_SIZE to 1024 * 1024,
                SettingsFrame.MAX_HEADER_LIST_SIZE to 64
            ), false
        )

        AioTcpServer(tcpConfig).onAcceptAsync { connection ->
            connection.startReadingAndAwaitHandshake()
            Http2ServerConnection(
                httpConfig, connection, SimpleFlowControlStrategy(),
                object : Http2Connection.Listener.Adapter() {

                    override fun onSettings(http2Connection: Http2Connection, frame: SettingsFrame) {
                        println("server receives settings: $frame")

                        if (frame.settings.equals(settingsFrame.settings)) {
                            channel.offer(frame)
                        }
                    }
                }
            )
        }.listen(host, port)

        val client = AioTcpClient(tcpConfig)
        val connection = client.connect(host, port).await()
        connection.startReadingAndAwaitHandshake()
        val http2Connection = Http2ClientConnection(
            httpConfig, connection, SimpleFlowControlStrategy(),
            object : Http2Connection.Listener.Adapter() {

                override fun onSettings(http2Connection: Http2Connection, frame: SettingsFrame) {
                    println("client receives settings: $frame")
                }
            }
        )

        http2Connection.settings(settingsFrame) {
            println("send settings success. $it")
        }

        val receivedSettings = withTimeout(2000) { channel.receive() }
        assertEquals(settingsFrame.settings, receivedSettings.settings)

        stopTest(http2Connection)
    }

    @Test
    fun testPing() = runBlocking {
        val host = "localhost"
        val port = 4020
        val count = 10
        val tcpConfig = TcpConfig(30, false)
        val httpConfig = HttpConfig()

        val receivedCount = AtomicInteger()
        val channel = Channel<Int>()


        AioTcpServer(tcpConfig).onAcceptAsync { connection ->
            connection.startReadingAndAwaitHandshake()
            Http2ServerConnection(
                httpConfig, connection, SimpleFlowControlStrategy(),
                Http2Connection.Listener.Adapter()
            )
        }.listen(host, port)

        val client = AioTcpClient(tcpConfig)
        val connection = client.connect(host, port).await()
        connection.startReadingAndAwaitHandshake()
        val http2Connection = Http2ClientConnection(
            httpConfig, connection, SimpleFlowControlStrategy(),
            object : Http2Connection.Listener.Adapter() {

                override fun onPing(http2Connection: Http2Connection, frame: PingFrame) {
                    println("Client receives the ping frame. ${frame.payloadAsLong}: ${frame.isReply}")
                    if (receivedCount.incrementAndGet() >= count) {
                        channel.offer(receivedCount.get())
                    }
                }
            }
        )

        (1..count).forEach {index ->
            val pingFrame = PingFrame(index.toLong(), false)
            http2Connection.ping(pingFrame) {
                println("send ping success. $it")
            }
        }

        val pingCount = withTimeout(2000) { channel.receive() }
        assertTrue(pingCount > 0)

        stopTest(http2Connection)
    }

    private fun stopTest(http2Connection: Http2ClientConnection) {
        try {
            val stopTime = measureTimeMillis {
                @Suppress("BlockingMethodInNonBlockingContext")
                http2Connection.close()
                stopAll()
            }
            println("stop success. $stopTime")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }



}