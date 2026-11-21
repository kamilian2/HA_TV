package pl.kamil.hatv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import java.net.Inet4Address
import java.net.NetworkInterface

const val API_PORT = 5003

fun Context.dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

fun parseColor(s: String?, def: Int): Int {
    if (s.isNullOrBlank()) return def
    return try {
        val c = s.trim()
        if (c.startsWith("#") || c.all { it.isLetter() }) Color.parseColor(c) else Color.parseColor("#$c")
    } catch (e: Exception) {
        def
    }
}

fun localIpAddresses(): List<String> = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
} catch (e: Exception) {
    emptyList()
}

/** Ikony Material Design ("mdi:lightbulb") z czcionki w assets. */
object Mdi {
    @Volatile private var map: Map<String, Int>? = null
    var typeface: Typeface? = null
        private set

    fun init(ctx: Context) {
        if (map != null) return
        synchronized(this) {
            if (map != null) return
            val m = HashMap<String, Int>(8000)
            ctx.assets.open("mdi.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val i = line.indexOf(' ')
                    if (i > 0) line.substring(i + 1).toIntOrNull(16)?.let { m[line.substring(0, i)] = it }
                }
            }
            typeface = Typeface.createFromAsset(ctx.assets, "mdi.ttf")
            map = m
        }
    }

    fun glyph(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val cp = map?.get(name.removePrefix("mdi:").trim()) ?: map?.get("help-circle-outline") ?: return ""
        return String(Character.toChars(cp))
    }
}

/** Zapis ostatniego błędu aplikacji (widoczny na stronie /log). */
fun installCrashLogger(ctx: Context) {
    val prev = Thread.getDefaultUncaughtExceptionHandler()
    if (prev is CrashLogger) return
    Thread.setDefaultUncaughtExceptionHandler(CrashLogger(ctx.applicationContext, prev))
}

class CrashLogger(
    private val ctx: Context,
    private val prev: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            java.io.File(ctx.filesDir, "crash.txt").writeText(java.util.Date().toString() + "\n" + sw)
        } catch (_: Exception) {}
        prev?.uncaughtException(t, e)
    }
}

fun lastCrash(ctx: Context): String = try {
    java.io.File(ctx.filesDir, "crash.txt").takeIf { it.exists() }?.readText() ?: ""
} catch (e: Exception) { "" }
