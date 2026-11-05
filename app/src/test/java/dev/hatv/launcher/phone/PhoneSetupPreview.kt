package dev.hatv.launcher.phone

import dev.hatv.launcher.data.*
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Development-only browser fixture. Not packaged in the APK and never contacts Home Assistant. */
object PhoneSetupPreview {
    @JvmStatic
    fun main(args: Array<String>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val done = CountDownLatch(1)
        var config = Config()
        var connected = false
        val apps =
            JSONArray()
                .put(JSONObject().put("id", "app.youtube").put("name", "YouTube"))
                .put(JSONObject().put("id", "app.arte").put("name", "arte"))
        val entities =
            Demo.entities.values +
                Entity("sensor.energy_total", "23", JSONObject().put("unit_of_measurement", "kWh"))
        val info = {
            JSONObject()
                .put("ok", true)
                .put("config", config.toJson())
                .put("hasToken", false)
                .put("connected", connected)
                .put("apps", apps)
                .put("selectedEntities", JSONArray())
        }
        val server =
            LocalSetupServer(
                scope,
                InetAddress.getByName("127.0.0.1"),
                { File(args[0], it).readBytes() },
                { j ->
                    when (j.getString("op")) {
                        "pair" ->
                            JSONObject()
                                .put("ok", true)
                                .put("phase", "paired")
                                .put("code", "123456")
                                .put("expiresIn", 600)
                        "info" -> info()
                        "connect" -> {
                            require(j.getString("token") == "fixture-token") {
                                "Use fixture-token in the development preview"
                            }
                            connected = true
                            config = config.copy(server = j.getString("server"), demo = false)
                            info()
                        }
                        "entities" -> {
                            val domain = j.getString("domain")
                            val query = j.optString("query")
                            val matches =
                                entities.filter {
                                    (if (domain == "controls")
                                        it.domain in
                                            listOf("light", "switch", "scene", "climate", "cover")
                                    else it.domain == domain) &&
                                        (domain != "sensor" ||
                                            it.attributes.optString("unit_of_measurement") in
                                                listOf("W", "kW", "MW")) &&
                                        (it.id.contains(query, true) ||
                                            it.name.contains(query, true))
                                }
                            JSONObject()
                                .put("ok", true)
                                .put("total", matches.size)
                                .put(
                                    "entities",
                                    JSONArray().apply {
                                        matches.forEach {
                                            put(
                                                JSONObject()
                                                    .put("id", it.id)
                                                    .put("name", it.name)
                                                    .put(
                                                        "unit",
                                                        it.attributes.optString(
                                                            "unit_of_measurement"
                                                        ),
                                                    )
                                                    .put("available", it.available)
                                            )
                                        }
                                    },
                                )
                        }
                        "apply" -> {
                            File(args[1] + ".result")
                                .writeText(j.getJSONObject("config").toString())
                            JSONObject().put("ok", true)
                        }
                        else -> error("Unknown fixture operation")
                    }
                },
                { done.countDown() },
            )
        File(args[1]).parentFile?.mkdirs()
        File(args[1]).writeText(JSONObject().put("url", server.url).toString())
        println("Browser fixture ready; link saved to ${args[1]}")
        done.await(10, java.util.concurrent.TimeUnit.MINUTES)
        Thread.sleep(1000)
        server.close()
        scope.cancel()
    }
}
