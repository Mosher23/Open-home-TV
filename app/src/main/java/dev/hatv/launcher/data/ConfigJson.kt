package dev.hatv.launcher.data

import org.json.JSONArray
import org.json.JSONObject

fun Config.toJson(): JSONObject =
    JSONObject()
        .put("server", server)
        .put("demo", demo)
        .put("location", location)
        .put("weather", weather)
        .put("controls", JSONArray(controls))
        .put("energy", JSONObject(energy))
        .put("powerDevices", JSONArray(powerDevices))
        .put(
            "cameras",
            JSONArray().apply {
                cameras.forEach {
                    put(
                        JSONObject().put("id", it.entityId).put("hls", it.hls).put("rtc", it.webRtc)
                    )
                }
            },
        )
        .put(
            "apps",
            JSONArray().apply {
                apps.forEach {
                    put(JSONObject().put("id", it.id).put("label", it.label).put("icon", it.icon))
                }
            },
        )
        .put("animate", animate)
        .put("livePreviews", livePreviews)
        .put("fahrenheit", fahrenheit)
        .put("darkPanels", darkPanels)
        .put("widgets", JSONArray(widgets))

fun parseConfig(j: JSONObject): Config {
    val energy = j.optJSONObject("energy") ?: JSONObject()
    val cameras = j.optJSONArray("cameras") ?: JSONArray()
    val apps = j.optJSONArray("apps") ?: JSONArray()
    return Config(
        server = j.optString("server"),
        demo = j.optBoolean("demo", true),
        location = j.optString("location", "Duisburg"),
        weather = j.optString("weather"),
        controls = j.optJSONArray("controls")?.strings() ?: emptyList(),
        energy = energy.keys().asSequence().associateWith { energy.getString(it) },
        powerDevices = j.optJSONArray("powerDevices")?.strings() ?: emptyList(),
        cameras =
            (0 until cameras.length()).map {
                cameras.getJSONObject(it).let { c ->
                    CameraConfig(c.getString("id"), c.optString("hls"), c.optString("rtc"))
                }
            },
        apps =
            (0 until apps.length()).map {
                apps.getJSONObject(it).let { a ->
                    AppShortcut(a.getString("id"), a.optString("label"), a.optString("icon"))
                }
            },
        animate = j.optBoolean("animate", true),
        livePreviews = j.optBoolean("livePreviews"),
        fahrenheit = j.optBoolean("fahrenheit"),
        darkPanels = j.optBoolean("darkPanels"),
        widgets =
            j.optJSONArray("widgets")?.strings()?.takeIf {
                it.size == 6 && it.toSet() == Config().widgets.toSet()
            } ?: Config().widgets,
    )
}
