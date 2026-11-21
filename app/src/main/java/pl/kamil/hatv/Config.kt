package pl.kamil.hatv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ustawienia aplikacji (zapisywane w pamięci telewizora).
 *
 *  url, token           – połączenie z Home Assistantem
 *  favorites            – lista pozycji na ekranie kafelków: "light.salon" albo "#Nagłówek"
 *  dashboards           – lista dashboardów do pokazania: [{"path":"lovelace","title":"Przegląd"}]
 *  start                – "tiles" albo ścieżka dashboardu, który ma się otwierać na starcie
 *  columns, tile_size   – wygląd kafelków
 *  zoom, cursor_speed   – dashboardy w przeglądarce
 */
class Config(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("hatv", Context.MODE_PRIVATE)
    private var data: JSONObject = try {
        JSONObject(prefs.getString("config", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    @Synchronized fun snapshot(): JSONObject = JSONObject(data.toString())

    val url: String get() = synchronized(this) { data.optString("url", "").trim().trimEnd('/') }
    val token: String get() = synchronized(this) { data.optString("token", "").trim() }
    val columns: Int get() = synchronized(this) { data.optInt("columns", 4).coerceIn(2, 8) }
    val tileSize: Int get() = synchronized(this) { data.optInt("tile_size", 100).coerceIn(60, 200) }
    val zoom: Int get() = synchronized(this) { data.optInt("zoom", 100).coerceIn(50, 200) }
    val cursorSpeed: Int get() = synchronized(this) { data.optInt("cursor_speed", 100).coerceIn(30, 300) }
    val start: String get() = synchronized(this) { data.optString("start", "tiles") }

    val favorites: List<String>
        get() = synchronized(this) {
            val a = data.optJSONArray("favorites") ?: return emptyList()
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        }

    /** Dashboardy wybrane do pokazania: (ścieżka, tytuł). */
    val dashboards: List<Pair<String, String>>
        get() = synchronized(this) {
            val a = data.optJSONArray("dashboards") ?: return emptyList()
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val p = o.optString("path", "")
                if (p.isBlank()) null else p to o.optString("title", p)
            }
        }

    /** Zmienia podane pola. Pusty token nie kasuje zapisanego. */
    fun update(patch: JSONObject) {
        synchronized(this) {
            for (k in patch.keys()) {
                if (k == "token" && patch.optString(k).isBlank()) continue
                if (k == "token_set") continue
                data.put(k, patch.get(k))
            }
            prefs.edit().putString("config", data.toString()).apply()
        }
        val ls = synchronized(listeners) { listeners.toList() }
        ls.forEach { it() }
    }

    /** Ustawienia bez tokenu (do strony WWW). */
    fun masked(): JSONObject = snapshot().also {
        it.put("token_set", it.optString("token").isNotBlank())
        it.put("token", "")
        if (!it.has("favorites")) it.put("favorites", JSONArray())
        if (!it.has("dashboards")) it.put("dashboards", JSONArray())
        it.put("columns", columns); it.put("tile_size", tileSize); it.put("zoom", zoom)
        it.put("cursor_speed", cursorSpeed); it.put("start", start)
    }
}
