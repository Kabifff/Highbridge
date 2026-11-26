// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.pro

import org.highbridge.app.data.NodeEntity
import org.highbridge.app.data.RouteEntity

/** A hop graph without GPS: rings by hop count, edges from traceroutes labelled with SNR. */
data class TopoGraph(val nodes: List<TopoNode>, val edges: List<TopoEdge>)
data class TopoNode(val key: String, val label: String, val protocol: String, val hops: Int)
data class TopoEdge(val from: String, val to: String, val snr: Float?)

object TopologyBuilder {
    fun build(myIds: Map<String, String>, nodes: List<NodeEntity>, routes: List<RouteEntity>, staleMs: Long, now: Long = System.currentTimeMillis()): TopoGraph {
        val fresh = nodes.filter { now - it.lastHeardMs <= staleMs || it.lastHeardMs == 0L }
        val topoNodes = myIds.map { (proto, id) -> TopoNode("$proto:$id", "You (${proto.take(2)})", proto, 0) } +
            fresh.map { TopoNode(it.key, it.shortName.ifBlank { it.id.takeLast(4) }, it.protocol, (it.hops ?: 0).coerceAtLeast(0) + 1) }
        val edges = mutableMapOf<Pair<String, String>, Float?>()
        for (r in routes.sortedBy { it.timeMs }) {
            val me = myIds[r.protocol]?.let { "${r.protocol}:$it" } ?: continue
            val hops = r.hops.split(',').filter { it.isNotBlank() }.map { "${r.protocol}:$it" }
            val snrs = r.snrs.split(',').mapNotNull { it.toFloatOrNull() }
            val chain = listOf(me) + hops + "${r.protocol}:${r.destId}"
            for (i in 0 until chain.lastIndex) edges[chain[i] to chain[i + 1]] = snrs.getOrNull(i)
        }
        // Direct neighbours with a known SNR get an edge to "you" too.
        fresh.filter { (it.hops ?: -1) == 0 }.forEach { n ->
            val me = myIds[n.protocol]?.let { "${n.protocol}:$it" } ?: return@forEach
            edges.putIfAbsent(me to n.key, n.snr)
        }
        val keys = topoNodes.map { it.key }.toSet()
        return TopoGraph(topoNodes, edges.filter { it.key.first in keys && it.key.second in keys }.map { TopoEdge(it.key.first, it.key.second, it.value) })
    }
}
