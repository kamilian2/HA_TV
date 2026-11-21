package pl.kamil.hatv

import org.json.JSONObject
import java.util.Locale

/** Wygląd i zachowanie encji według domeny (light, switch, cover…). */
object Entities {

    fun domain(id: String) = id.substringBefore('.')

    fun name(s: JSONObject?, id: String): String =
        s?.optJSONObject("attributes")?.optString("friendly_name", "")?.ifBlank { null } ?: id

    private val ACTIVE = setOf(
        "on", "open", "opening", "closing", "playing", "unlocked", "unlocking", "home", "heat", "cool",
        "heat_cool", "auto", "dry", "fan_only", "cleaning", "armed_away", "armed_home", "armed_night",
        "triggered", "problem", "detected"
    )

    fun isActive(s: JSONObject?): Boolean = s != null && s.optString("state") in ACTIVE

    fun isUnavailable(s: JSONObject?) = s == null || s.optString("state") in setOf("unavailable", "unknown")

    private fun num(v: Double): String =
        if (v == Math.floor(v) && Math.abs(v) < 1e9) v.toLong().toString()
        else String.format(Locale("pl", "PL"), "%.1f", v)

    fun stateText(id: String, s: JSONObject?): String {
        if (s == null) return "brak encji"
        val st = s.optString("state")
        val a = s.optJSONObject("attributes") ?: JSONObject()
        if (st == "unavailable") return "niedostępne"
        if (st == "unknown") return "—"
        return when (domain(id)) {
            "light" -> if (st == "on") {
                val b = a.optInt("brightness", -1)
                if (b >= 0) "Włączone · ${Math.round(b / 2.55)}%" else "Włączone"
            } else "Wyłączone"
            "switch", "input_boolean", "fan", "automation", "siren", "humidifier", "remote" ->
                if (st == "on") "Włączone" else if (st == "off") "Wyłączone" else st
            "binary_sensor" -> binaryText(a.optString("device_class"), st == "on")
            "cover" -> when (st) {
                "open" -> a.optInt("current_position", -1).let { if (it in 1..99) "Otwarte $it%" else "Otwarte" }
                "closed" -> "Zamknięte"
                "opening" -> "Otwiera się"
                "closing" -> "Zamyka się"
                else -> st
            }
            "lock" -> when (st) { "locked" -> "Zamknięty"; "unlocked" -> "Otwarty"; else -> st }
            "climate" -> {
                val cur = a.opt("current_temperature")
                val tgt = a.opt("temperature")
                val parts = mutableListOf<String>()
                if (cur is Number) parts.add(num(cur.toDouble()) + "°")
                if (tgt is Number) parts.add("→ " + num(tgt.toDouble()) + "°")
                parts.add(hvacName(st))
                parts.joinToString(" ")
            }
            "media_player" -> {
                val t = a.optString("media_title", "")
                val base = when (st) { "playing" -> "Odtwarza"; "paused" -> "Pauza"; "idle" -> "Bezczynny"; "off" -> "Wyłączony"; "on" -> "Włączony"; else -> st }
                if (t.isNotBlank()) "$base · $t" else base
            }
            "person", "device_tracker" -> when (st) { "home" -> "W domu"; "not_home" -> "Poza domem"; else -> st }
            "alarm_control_panel" -> when (st) {
                "disarmed" -> "Rozbrojony"; "armed_away" -> "Uzbrojony (poza domem)"; "armed_home" -> "Uzbrojony (w domu)"
                "armed_night" -> "Uzbrojony (noc)"; "triggered" -> "ALARM!"; "arming" -> "Uzbrajanie…"; else -> st
            }
            "scene", "script", "button", "input_button" -> "Uruchom"
            "vacuum" -> when (st) { "cleaning" -> "Sprząta"; "docked" -> "W stacji"; "returning" -> "Wraca"; else -> st }
            else -> {
                val unit = a.optString("unit_of_measurement", "")
                val d = st.toDoubleOrNull()
                val v = if (d != null) num(Math.round(d * 10) / 10.0) else st
                if (unit.isNotBlank()) "$v $unit" else v
            }
        }
    }

    fun hvacName(m: String) = when (m) {
        "off" -> "wył."; "heat" -> "grzanie"; "cool" -> "chłodzenie"; "heat_cool" -> "grzanie/chłodzenie"
        "auto" -> "auto"; "dry" -> "osuszanie"; "fan_only" -> "wentylator"; else -> m
    }

    private fun binaryText(dc: String, on: Boolean): String = when (dc) {
        "door", "garage_door", "opening", "window" -> if (on) "Otwarte" else "Zamknięte"
        "motion", "occupancy", "presence" -> if (on) "Wykryto ruch" else "Brak ruchu"
        "moisture" -> if (on) "Mokro" else "Sucho"
        "smoke", "gas", "co", "problem", "safety" -> if (on) "ALARM" else "OK"
        "connectivity" -> if (on) "Połączono" else "Rozłączono"
        "lock" -> if (on) "Otwarty" else "Zamknięty"
        "plug", "power", "running" -> if (on) "Działa" else "Nie działa"
        else -> if (on) "Włączone" else "Wyłączone"
    }

    fun icon(id: String, s: JSONObject?): String {
        val custom = s?.optJSONObject("attributes")?.optString("icon", "") ?: ""
        if (custom.isNotBlank()) return custom
        val on = isActive(s)
        val dc = s?.optJSONObject("attributes")?.optString("device_class", "") ?: ""
        return when (domain(id)) {
            "light" -> if (on) "mdi:lightbulb-on" else "mdi:lightbulb-outline"
            "switch" -> if (on) "mdi:toggle-switch" else "mdi:toggle-switch-off-outline"
            "input_boolean" -> if (on) "mdi:check-circle" else "mdi:checkbox-blank-circle-outline"
            "fan" -> if (on) "mdi:fan" else "mdi:fan-off"
            "cover" -> when (dc) {
                "garage" -> if (on) "mdi:garage-open" else "mdi:garage"
                "gate" -> if (on) "mdi:gate-open" else "mdi:gate"
                "door" -> if (on) "mdi:door-open" else "mdi:door-closed"
                else -> if (on) "mdi:window-shutter-open" else "mdi:window-shutter"
            }
            "lock" -> if (s?.optString("state") == "locked") "mdi:lock" else "mdi:lock-open-variant"
            "climate" -> "mdi:thermostat"
            "media_player" -> if (s?.optString("state") == "playing") "mdi:play-circle" else "mdi:cast"
            "scene" -> "mdi:palette"
            "script" -> "mdi:script-text-play"
            "automation" -> "mdi:robot"
            "button", "input_button" -> "mdi:gesture-tap-button"
            "person" -> "mdi:account"
            "device_tracker" -> "mdi:map-marker"
            "camera" -> "mdi:cctv"
            "vacuum" -> "mdi:robot-vacuum"
            "alarm_control_panel" -> "mdi:shield-home"
            "weather" -> "mdi:weather-partly-cloudy"
            "number", "input_number" -> "mdi:ray-vertex"
            "select", "input_select" -> "mdi:format-list-bulleted"
            "binary_sensor" -> when (dc) {
                "door" -> if (on) "mdi:door-open" else "mdi:door-closed"
                "window" -> if (on) "mdi:window-open" else "mdi:window-closed"
                "motion", "occupancy", "presence" -> if (on) "mdi:motion-sensor" else "mdi:motion-sensor-off"
                "moisture" -> "mdi:water"
                "smoke" -> "mdi:smoke-detector"
                else -> if (on) "mdi:checkbox-marked-circle" else "mdi:checkbox-blank-circle-outline"
            }
            "sensor" -> when (dc) {
                "temperature" -> "mdi:thermometer"
                "humidity" -> "mdi:water-percent"
                "power" -> "mdi:flash"
                "energy" -> "mdi:lightning-bolt"
                "battery" -> "mdi:battery"
                "illuminance" -> "mdi:brightness-5"
                "pressure" -> "mdi:gauge"
                "voltage" -> "mdi:sine-wave"
                "current" -> "mdi:current-ac"
                "gas" -> "mdi:meter-gas"
                "water" -> "mdi:water"
                "monetary" -> "mdi:cash"
                else -> "mdi:eye"
            }
            else -> "mdi:help-circle-outline"
        }
    }

    /** Główna akcja przycisku OK: (domena usługi, usługa) albo null = otwórz szczegóły. */
    fun primaryAction(id: String, s: JSONObject?): Pair<String, String>? {
        val st = s?.optString("state") ?: return null
        return when (domain(id)) {
            "light", "switch", "input_boolean", "fan", "automation", "siren", "humidifier" -> domain(id) to "toggle"
            "cover" -> "cover" to when (st) { "opening", "closing" -> "stop_cover"; "open" -> "close_cover"; else -> "open_cover" }
            "lock" -> "lock" to if (st == "locked") "unlock" else "lock"
            "scene" -> "scene" to "turn_on"
            "script" -> "script" to "turn_on"
            "button" -> "button" to "press"
            "input_button" -> "input_button" to "press"
            "media_player" -> "media_player" to "media_play_pause"
            "vacuum" -> "vacuum" to if (st == "cleaning") "return_to_base" else "start"
            else -> null
        }
    }

    /** Domeny, które warto pokazać, gdy nie wybrano ulubionych. */
    val DEFAULT_DOMAINS = listOf(
        "light", "switch", "cover", "climate", "lock", "media_player", "scene", "script", "fan",
        "input_boolean", "vacuum", "alarm_control_panel"
    )

    val DOMAIN_NAMES = mapOf(
        "light" to "Światła", "switch" to "Przełączniki", "cover" to "Rolety i bramy", "climate" to "Ogrzewanie i klimatyzacja",
        "lock" to "Zamki", "media_player" to "Multimedia", "scene" to "Sceny", "script" to "Skrypty", "fan" to "Wentylatory",
        "input_boolean" to "Pomocnicze", "vacuum" to "Odkurzacze", "alarm_control_panel" to "Alarm", "sensor" to "Czujniki",
        "binary_sensor" to "Czujniki stanu", "automation" to "Automatyzacje", "person" to "Osoby"
    )
}
