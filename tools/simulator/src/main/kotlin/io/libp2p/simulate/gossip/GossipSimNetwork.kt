package io.libp2p.simulate.gossip

import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.simulate.Network
import io.libp2p.simulate.generateAndConnect
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.stream.StreamSimConnection
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

typealias GossipRouterBuilderFactory = (Int) -> SimGossipRouterBuilder
typealias GossipSimPeerModifier = (Int, GossipSimPeer) -> Unit

class GossipSimNetwork(
    val cfg: GossipSimConfig,
    val routerFactory: GossipRouterBuilderFactory = { SimGossipRouterBuilder() },
    val simPeerModifier: GossipSimPeerModifier = { _, _ -> }
) {
    val peers = sortedMapOf<Int, GossipSimPeer>()
    lateinit var network: Network

    val timeController = TimeControllerImpl()
    val commonRnd = Random(cfg.startRandomSeed)
    val commonExecutor = ControlledExecutorServiceImpl(timeController)

    protected fun createSimPeer(number: Int): GossipSimPeer {
        val additionalHeartbeatDelay = cfg.additionalHeartbeatDelay.newValue(commonRnd)
        val routerBuilder = routerFactory(number).also {
            it.params = cfg.gossipParams
            it.scoreParams = cfg.gossipScoreParams
            it.additionalHeartbeatDelay = additionalHeartbeatDelay.next().toLong().milliseconds
        }

        val simPeer = GossipSimPeer(number, commonRnd)
        simPeer.routerBuilder = routerBuilder
        simPeer.simExecutor = commonExecutor
        simPeer.currentTime = { timeController.time }
        simPeer.msgSizeEstimator = cfg.messageGenerator.sizeEstimator

        val (inbound, outbound) = cfg.bandwidthGenerator(simPeer)
        simPeer.inboundBandwidth = inbound
        simPeer.outboundBandwidth = outbound
        simPeer.msgSizeEstimator = cfg.messageGenerator.sizeEstimator
        simPeerModifier(number, simPeer)
        return simPeer
    }

    fun createAllPeers() {
        peers += (0 until cfg.totalPeers).map {
            it to createSimPeer(it)
        }
    }

    fun connectAllPeers() {
        cfg.topology.random = commonRnd
        network = cfg.topology.generateAndConnect(peers.values.toList())
        network.activeConnections.forEach {
            val latency = cfg.latencyGenerator(it as StreamSimConnection)
            it.connectionLatency = latency
        }
    }
}
