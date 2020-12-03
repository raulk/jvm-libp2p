package io.libp2p.security

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.multistream.Negotiator
import io.libp2p.multistream.ProtocolSelect
import io.libp2p.tools.TestChannel
import io.libp2p.tools.TestChannel.Companion.interConnect
import io.libp2p.tools.TestHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ResourceLeakDetector
import org.apache.logging.log4j.LogManager
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

typealias SecureChannelCtor = (PrivKey) -> SecureChannel

val logger = LogManager.getLogger(SecureChannelTest::class.java)

abstract class SecureChannelTest(
    val secureChannelCtor: SecureChannelCtor,
    val announce: String
) {
    init {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)
    }

    companion object {
        @JvmStatic
        fun plainDataSizes() = listOf(
            0,
            16,
            65535 - 16, // max length fitting to a single Noise frame
            65535 - 15, // min length exceeding a single Noise frame
            65535,
            65536
        )
    }

    @ParameterizedTest
    @MethodSource("plainDataSizes")
    fun secureInterconnect(dataSize: Int) {
        val (privKey1, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKey2, _) = generateKeyPair(KEY_TYPE.ECDSA)

        val protocolSelect1 = makeSelector(privKey1)
        val protocolSelect2 = makeSelector(privKey2)

        val eCh1 = makeChannel("#1", true, protocolSelect1)
        val eCh2 = makeChannel("#2", false, protocolSelect2)

        logger.info("Connecting channels...")
        interConnect(eCh1, eCh2)

        logger.info("Waiting for negotiation to complete...")
        protocolSelect1.selectedFuture.get(10, TimeUnit.SECONDS)
        protocolSelect2.selectedFuture.get(10, TimeUnit.SECONDS)
        logger.info("Secured!")

        val data1: String
        val data2: String
        if (dataSize >= 2) {
            val trimDataSize = dataSize - 2
            val data = (0 until trimDataSize / 10)
                .map { " x" + Integer.toHexString(it * 10).padStart(8, '0') }
                .joinToString("")
                .padEnd(trimDataSize, '_')
            data1 = "1-$data"
            data2 = "2-$data"
        } else {
            data1 = if (dataSize == 1) "1" else ""
            data2 = if (dataSize == 1) "2" else ""
        }

        val handler1 = SecureChannelTestHandler("1", data1)
        val handler2 = SecureChannelTestHandler("2", data2)

        eCh1.pipeline().addLast(handler1)
        eCh2.pipeline().addLast(handler2)

        eCh1.pipeline().fireChannelActive()
        eCh2.pipeline().fireChannelActive()

        while (data2 != handler1.getAllReceived()) {
            val nextChunk = handler1.receivedQueue.poll(30, TimeUnit.SECONDS)
            logger.info("handler1 received chunk of size ${nextChunk?.length}")
            if (nextChunk == null) {
                fail<Any>("Didn't receive all the data1: '${handler1.getAllReceived()}', data2: '${handler2.getAllReceived()}'")
            }
        }
        while (data1 != handler2.getAllReceived()) {
            val nextChunk = handler2.receivedQueue.poll(30, TimeUnit.SECONDS)
            logger.info("handler2 received chunk of size ${nextChunk?.length}")
            if (nextChunk == null) {
                fail<Any>("Didn't receive all the data2: '${handler2.getAllReceived()}'")
            }
        }

        throw RuntimeException("Test")
    } // secureInterconnect

    protected fun makeSelector(key: PrivKey) = ProtocolSelect(listOf(secureChannelCtor(key)))

    protected fun makeChannel(
        name: String,
        initiator: Boolean,
        selector: ChannelInboundHandlerAdapter
    ): TestChannel {
        val negotiator = if (initiator) {
            Negotiator.createRequesterInitializer(announce)
        } else {
            Negotiator.createResponderInitializer(listOf(ProtocolMatcher.strict(announce)))
        }

        return TestChannel(
            name,
            initiator,
//            LoggingHandler(name, LogLevel.ERROR),
            negotiator,
            selector
        )
    } // makeChannel

    class SecureChannelTestHandler(
        name: String,
        val data: String = "Hello World from $name"
    ) : TestHandler(name) {

        val received = Collections.synchronizedList(mutableListOf<String>())
        val receivedQueue = LinkedBlockingQueue<String>()

        override fun channelRegistered(ctx: ChannelHandlerContext?) {
            logger.info("SecureChannelTestHandler $name: channelRegistered")
            super.channelRegistered(ctx)
        }

        override fun channelUnregistered(ctx: ChannelHandlerContext?) {
            logger.info("SecureChannelTestHandler $name: channelUnregistered")
            super.channelUnregistered(ctx)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
            logger.info("SecureChannelTestHandler $name: exceptionCaught: $cause")
            ctx?.fireExceptionCaught(cause)
        }

        override fun channelActive(ctx: ChannelHandlerContext) {
            super.channelActive(ctx)
            logger.info("SecureChannelTestHandler $name: channelActive")
            ctx.writeAndFlush(data.toByteArray().toByteBuf())
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
            msg as ByteBuf
            logger.info("SecureChannelTestHandler $name: channelRead $msg")
            val receivedCunk = msg.toByteArray().toString(StandardCharsets.UTF_8)
            logger.debug("==$name== read: $receivedCunk")
            received += receivedCunk
            receivedQueue += receivedCunk
        }

        fun getAllReceived() = received.joinToString("")
    } // SecureChannelTestHandler
} // class SecureChannelTest
