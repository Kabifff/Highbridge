// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.rns

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import org.highbridge.protocol.reticulum.RnsEngine
import org.highbridge.protocol.reticulum.RnsListener

/** Python calls these static methods (see app/src/main/python/highbridge_rns.py). */
object RnsBridge {
    @Volatile var listener: RnsListener? = null
    @Volatile var writer: ((ByteArray) -> Unit)? = null

    @JvmStatic fun onMessage(source: String, title: String, content: String, timeMs: Long, snr: Double) =
        listener?.onMessage(source, title, content, timeMs, if (snr <= -998.0) null else snr.toFloat())
    @JvmStatic fun onGroupMessage(index: Int, sender: String, text: String) = listener?.onGroupMessage(index, sender, text)
    @JvmStatic fun onAnnounce(hash: String, name: String, aspect: String, hops: Int) = listener?.onAnnounce(hash, name, aspect, hops)
    @JvmStatic fun onDelivery(id: String, ok: Boolean) = listener?.onDelivery(id, ok)
    @JvmStatic fun onLog(line: String) = listener?.onLog(line)
    @JvmStatic fun rnodeWrite(data: ByteArray) { writer?.invoke(data) }
}

/** Runs the real Reticulum (RNS) and LXMF Python packages inside the app. */
class ChaquopyRnsEngine(context: android.content.Context) : RnsEngine {
    private val ctx = context.applicationContext
    private val mod: PyObject by lazy {
        // Started on first use (not at app launch) so opening the app stays fast for people who don't use Reticulum.
        if (!Python.isStarted()) Python.start(com.chaquo.python.android.AndroidPlatform(ctx))
        Python.getInstance().getModule("highbridge_rns")
    }

    override var listener: RnsListener?
        get() = RnsBridge.listener
        set(v) { RnsBridge.listener = v }
    override var rnodeWriter: ((ByteArray) -> Unit)?
        get() = RnsBridge.writer
        set(v) { RnsBridge.writer = v }

    override fun start(o: RnsEngine.StartOptions): String {
        check(RnsInstaller.isInstalled(ctx)) { "Reticulum isn't downloaded yet. Open Connect a radio › Reticulum to get it." }
        mod.callAttr("set_ext_path", RnsInstaller.dir(ctx).absolutePath)
        return mod.callAttr("start", o.configDir, o.autoInterface, o.tcpHosts.joinToString(","), o.useRNode, o.rnodeBitrate, o.displayName).toString()
    }
    override fun stop() { mod.callAttr("stop") }
    override fun announce() { mod.callAttr("announce") }
    override fun sendLxmf(destHex: String, text: String): String = mod.callAttr("send_lxmf", destHex, text).toString()
    override fun joinGroup(index: Int, name: String, key: ByteArray): String = mod.callAttr("join_group", index, name, key).toString()
    override fun sendGroup(index: Int, sender: String, text: String): String = mod.callAttr("send_group", index, sender, text).toString()
    override fun fetchPage(nodeHex: String, path: String, timeoutSec: Int): String = mod.callAttr("fetch_page", nodeHex, path, timeoutSec).toString()
    override fun rnodeInbound(packet: ByteArray) { mod.callAttr("rnode_inbound", packet) }
    override fun status(): String = mod.callAttr("status").toString()
}
