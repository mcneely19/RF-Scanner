package com.rfscout.app

import android.content.Context
import java.io.IOException

/**
 * Vendor names for signals.
 *
 * - Bluetooth company IDs (from manufacturer data) come from a small built-in table of common vendors.
 * - Wi-Fi and Bluetooth addresses are looked up in the IEEE vendor registry, a CSV the build downloads
 *   into the app's assets (see the build workflow). Without that file only company IDs are named.
 * - Phones and many accessories use private (random) addresses, which carry no vendor information.
 */
object Vendors {

    // Bluetooth SIG company identifiers. Common vendors only; anything else is shown as a hex ID.
    private val companies = mapOf(
        0x0002 to "Intel",
        0x0003 to "IBM",
        0x0006 to "Microsoft",
        0x000D to "Texas Instruments",
        0x000F to "Broadcom",
        0x001D to "Qualcomm",
        0x0030 to "STMicroelectronics",
        0x0046 to "MediaTek",
        0x004C to "Apple, Inc.",
        0x0059 to "Nordic Semiconductor",
        0x0075 to "Samsung Electronics",
        0x0087 to "Garmin",
        0x009E to "Bose",
        0x00D2 to "Dialog Semiconductor",
        0x00E0 to "Google",
        0x012D to "Sony",
        0x0171 to "Amazon",
        0x02E5 to "Espressif",
        0x09C8 to "XUNTONG"
    )

    @Volatile
    private var oui: HashMap<String, String>? = null

    @Volatile
    private var state = "not loaded"

    /** Start loading the registry in the background. Safe to call more than once. */
    fun init(ctx: Context) {
        if (state != "not loaded") return
        state = "loading"
        val app = ctx.applicationContext
        Thread {
            try {
                val map = HashMap<String, String>(50_000)
                app.assets.open("oui.csv").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val f = splitCsv(line)
                        if (f.size >= 3 && f[1].length == 6) map[f[1].uppercase()] = f[2]
                    }
                }
                oui = map
                state = "loaded ${map.size}"
            } catch (_: IOException) {
                state = "not included"
            } catch (_: Exception) {
                state = "unreadable"
            }
        }.start()
    }

    fun registryState(): String = state

    fun company(id: Int): String? = companies[id]

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(4)
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun addressOf(t: Track): String? =
        when (t.protocol) {
            Protocol.WIFI, Protocol.BLE, Protocol.BT_CLASSIC -> t.key.substringAfter(':', "")
            else -> null
        }?.takeIf { it.length >= 17 }

    private fun firstByte(addr: String): Int = addr.substring(0, 2).toIntOrNull(16) ?: 0

    /** Registered vendor of an address, or null if unknown, not loaded, or the address is private. */
    private fun registryVendor(addr: String): String? {
        val m = oui ?: return null
        return m[addr.replace(":", "").replace("-", "").uppercase().take(6)]
    }

    private fun looksPrivate(t: Track, addr: String): Boolean {
        val b = firstByte(addr)
        if ((b and 0x02) != 0) return true                           // locally administered
        return t.protocol == Protocol.BLE && oui != null && (b and 0xC0) != 0 && registryVendor(addr) == null
    }

    /** One short line for the signal list, or null. */
    fun shortLine(t: Track): String? {
        val parts = ArrayList<String>()
        t.mfrId?.let { id -> company(id)?.let { parts.add(it) } }
        val addr = addressOf(t)
        if (addr != null) {
            if (looksPrivate(t, addr)) {
                parts.add("private address")
            } else {
                registryVendor(addr)?.let { v -> if (!parts.contains(v)) parts.add(v) }
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /** Lines for the details page. */
    fun detailLines(t: Track): String {
        val sb = StringBuilder()
        t.mfrId?.let { id ->
            sb.append("Bluetooth company: ")
                .append(company(id) ?: "unlisted (ID 0x%04X)".format(id))
                .append('\n')
        }
        val addr = addressOf(t)
        if (addr != null) {
            sb.append("Address vendor: ")
            if (looksPrivate(t, addr)) {
                sb.append(
                    if (t.protocol == Protocol.WIFI) "none: virtual, guest or randomized address"
                    else "none: private (random) address"
                )
            } else {
                val v = registryVendor(addr)
                sb.append(
                    when {
                        v != null -> "$v (the chip or device maker, not always the brand)"
                        oui == null -> "registry ${registryState()}"
                        else -> "not in the registry"
                    }
                )
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}
