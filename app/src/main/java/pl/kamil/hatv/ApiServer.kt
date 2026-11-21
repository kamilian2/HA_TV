package pl.kamil.hatv

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Wbudowany serwer: strona ustawień (GET /) i proste API.
 *
 *  GET  /              strona ustawień w przeglądarce
 *  GET  /config        ustawienia (bez tokenu) + stan połączenia
 *  GET  /entities      wszystkie encje z HA (do wyboru ulubionych)
 *  GET  /dashboards    dashboardy z HA
 *  GET  /log           ostatni błąd aplikacji
 *  POST /settings      zmiana ustawień: {"url","token","favorites":[...],"dashboards":[...],"start","columns",...}
 *  POST /test          sprawdzenie adresu i tokenu: {"url","token"} (puste = zapisane)
 *  POST /call          wywołanie usługi HA: {"domain","service","entity_id","data":{}}
 */
class ApiServer(port: Int, private val ctx: Context, private val version: String) : NanoHTTPD(port) {

    private val app get() = App.instance

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri.trimEnd('/').ifEmpty { "/" }
            if (session.method == Method.OPTIONS) return json(JSONObject().put("ok", true))
            if (session.method == Method.GET) {
                return when (uri) {
                    "/", "/index.html" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                        ctx.assets.open("web/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() })
                    "/config" -> json(JSONObject().put("ok", true).put("version", version)
                        .put("config", app.config.masked())
                        .put("status", app.ha.status).put("connected", app.ha.connected)
                        .put("ip", JSONArray(localIpAddresses())))
                    "/entities" -> json(JSONObject().put("ok", true).put("entities", entities()))
                    "/dashboards" -> json(JSONObject().put("ok", true).put("dashboards", dashboards()))
                    "/log" -> newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8",
                        lastCrash(ctx).ifEmpty { "Brak zapisanych błędów." })
                    else -> json(JSONObject().put("ok", false).put("error", "Nieznany adres"), Response.Status.NOT_FOUND)
                }
            }
            val j = readBody(session).let { if (it.isBlank()) JSONObject() else JSONObject(it) }
            when (uri) {
                "/settings" -> {
                    app.config.update(j)
                    json(JSONObject().put("ok", true).put("config", app.config.masked()))
                }
                "/test" -> {
                    val url = j.optString("url", "").ifBlank { app.config.url }.trim().trimEnd('/')
                    val token = j.optString("token", "").ifBlank { app.config.token }.trim()
                    if (url.isEmpty() || token.isEmpty()) throw IllegalArgumentException("Podaj adres i token")
                    json(JSONObject().put("ok", true).put("message", app.ha.test(url, token)))
                }
                "/call" -> {
                    val latch = CountDownLatch(1)
                    var res = JSONObject().put("ok", false).put("error", "przekroczono czas")
                    app.ha.callService(j.optString("domain"), j.optString("service"),
                        j.optString("entity_id").ifBlank { null }, j.optJSONObject("data")) { ok, msg ->
                        res = JSONObject().put("ok", ok).put("error", msg); latch.countDown()
                    }
                    latch.await(8, TimeUnit.SECONDS)
                    json(res, if (res.optBoolean("ok")) Response.Status.OK else Response.Status.BAD_REQUEST)
                }
                else -> json(JSONObject().put("ok", false).put("error", "Nieznany adres"), Response.Status.NOT_FOUND)
            }
        } catch (e: Exception) {
            json(JSONObject().put("ok", false).put("error", e.message ?: e.toString()), Response.Status.BAD_REQUEST)
        }
    }

    private fun entities(): JSONArray {
        val out = JSONArray()
        val list = app.ha.states.entries.sortedBy { it.key }
        for ((id, s) in list) {
            out.put(JSONObject()
                .put("entity_id", id)
                .put("domain", Entities.domain(id))
                .put("name", Entities.name(s, id))
                .put("state", Entities.stateText(id, s))
                .put("area", s.optJSONObject("attributes")?.optString("area", "") ?: ""))
        }
        return out
    }

    private fun dashboards(): JSONArray {
        val latch = CountDownLatch(1)
        var list: List<Pair<String, String>> = emptyList()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            app.ha.listDashboards { l -> list = l; latch.countDown() }
        }
        latch.await(6, TimeUnit.SECONDS)
        val out = JSONArray()
        for ((p, t) in list) out.put(JSONObject().put("path", p).put("title", t))
        return out
    }

    private fun readBody(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var read = 0
        val input = session.inputStream
        while (read < len) {
            val r = input.read(buf, read, len - read)
            if (r < 0) break
            read += r
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun json(o: JSONObject, status: Response.Status = Response.Status.OK): Response {
        val r = newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }
}
