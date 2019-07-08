package com.fireflysource.net.tcp.aio

import com.fireflysource.common.sys.SystemLogger
import com.fireflysource.net.tcp.TcpConnection
import com.fireflysource.net.tcp.TcpServer
import com.fireflysource.net.tcp.secure.SecureEngineFactory
import com.fireflysource.net.tcp.secure.conscrypt.DefaultCredentialConscryptSSLContextFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import java.net.SocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.function.Consumer

/**
 * @author Pengtao Qiu
 */
class AioTcpServer(val config: TcpConfig = TcpConfig()) : AbstractAioTcpChannelGroup(), TcpServer {

    companion object {
        private val log = SystemLogger.create(AioTcpServer::class.java)
    }

    private val connChannel = Channel<TcpConnection>(UNLIMITED)
    private var connectionConsumer: Consumer<TcpConnection> = Consumer { connChannel.offer(it) }
    private var secureEngineFactory: SecureEngineFactory = DefaultCredentialConscryptSSLContextFactory()
    private var supportedProtocols: List<String> = defaultSupportedProtocols
    private var peerHost: String = ""
    private var peerPort: Int = 0

    override fun getTcpConnectionChannel(): Channel<TcpConnection> = connChannel

    override fun secureEngineFactory(secureEngineFactory: SecureEngineFactory): TcpServer {
        this.secureEngineFactory = secureEngineFactory
        return this
    }

    override fun supportedProtocols(supportedProtocols: List<String>): TcpServer {
        this.supportedProtocols = supportedProtocols
        return this
    }

    override fun peerHost(peerHost: String): TcpServer {
        this.peerHost = peerHost
        return this
    }

    override fun peerPort(peerPort: Int): TcpServer {
        this.peerPort = peerPort
        return this
    }

    override fun enableSecureConnection(): TcpServer {
        config.enableSecureConnection = true
        return this
    }

    override fun timeout(timeout: Long): TcpServer {
        config.timeout = timeout
        return this
    }

    override fun onAccept(consumer: Consumer<TcpConnection>): TcpServer {
        connectionConsumer = consumer
        return this
    }

    override fun listen(address: SocketAddress): TcpServer {
        if (isStarted) return this

        start()

        try {
            val serverSocketChannel = AsynchronousServerSocketChannel.open(group)
            serverSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, config.reuseAddr)
            serverSocketChannel.bind(address, config.backlog)
            accept(serverSocketChannel)
        } catch (e: Exception) {
            log.error(e) { "bind server address exception" }
        }
        return this
    }

    private fun accept(serverSocketChannel: AsynchronousServerSocketChannel) {
        if (closed.get()) {
            return
        }

        serverSocketChannel.accept(
            id.getAndIncrement(),
            object : CompletionHandler<AsynchronousSocketChannel, Int> {
                override fun completed(socketChannel: AsynchronousSocketChannel, connectionId: Int) {
                    try {
                        val tcpConnection = AioTcpConnection(
                            connectionId, config.timeout,
                            socketChannel, getMessageThread(connectionId)
                        )
                        if (config.enableSecureConnection) {
                            val secureEngine = if (peerHost.isNotBlank() && peerPort != 0) {
                                secureEngineFactory.create(
                                    tcpConnection, false,
                                    peerHost, peerPort,
                                    supportedProtocols
                                )
                            } else {
                                secureEngineFactory.create(tcpConnection, false, supportedProtocols)
                            }
                            val secureConnection =
                                AioSecureTcpConnection(tcpConnection, secureEngine, getMessageThread(connectionId))
                            connectionConsumer.accept(secureConnection)
                        } else {
                            connectionConsumer.accept(tcpConnection)
                        }
                        log.debug { "accept the client connection. $connectionId" }
                    } catch (e: Exception) {
                        log.warn(e) { "accept connection exception. $connectionId" }
                    } finally {
                        accept(serverSocketChannel)
                    }
                }

                override fun failed(e: Throwable, connectionId: Int) {
                    log.warn(e) { "accept connection failure. $connectionId" }
                    accept(serverSocketChannel)
                }
            })
    }

    override fun getThreadName() = "aio-tcp-server"

    override fun destroy() {
        connChannel.close()
        super.destroy()
    }
}