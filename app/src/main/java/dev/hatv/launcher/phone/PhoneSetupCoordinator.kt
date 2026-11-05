package dev.hatv.launcher.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import dev.hatv.launcher.data.*
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class PhoneSetupState(
    val phase: String = "closed",
    val url: String = "",
    val code: String = "",
    val expiresAt: Long = 0,
    val error: String = "",
)

class PhoneSetupCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: () -> Config,
    private val token: () -> String,
    private val apps: () -> List<InstalledApp>,
    private val fetch: suspend (String, String) -> Map<String, Entity>,
    private val apply: suspend (Config, String?) -> Unit,
    private val completed: () -> Unit,
    private val lifetimeMillis: Long = 10 * 60_000L,
) {
    private val mutableState = MutableStateFlow(PhoneSetupState())
    val state = mutableState.asStateFlow()
    private var server: LocalSetupServer? = null
    private var expiry: Job? = null
    private var startJob: Job? = null
    private val lock = Mutex()
    private var owner = ""
    private var draft = Config()
    private var draftToken: String? = null
    private var discovered = emptyMap<String, Entity>()
    private var loaded = false
    private var generation = 0

    fun start() {
        stop()
        val run = generation
        draft = config()
        mutableState.value = PhoneSetupState(phase = "starting")
        startJob =
            scope.launch {
                var opening: LocalSetupServer? = null
                try {
                    val address = withContext(Dispatchers.IO) { lanAddress(context) }
                    val assets =
                        withContext(Dispatchers.IO) {
                            listOf("index.html", "setup.js").associateWith { name ->
                                context.assets.open("phone/$name").use { it.readBytes() }
                            }
                        }
                    if (run != generation) return@launch
                    val instance =
                        withContext(Dispatchers.IO) {
                            LocalSetupServer(
                                    scope,
                                    address,
                                    { assets.getValue(it) },
                                    { handle(run, it) },
                                    {
                                        scope.launch {
                                            delay(600)
                                            if (run == generation) {
                                                stop("done")
                                                completed()
                                            }
                                        }
                                    },
                                )
                                .also { opening = it }
                        }
                    if (run != generation) {
                        instance.close()
                        return@launch
                    }
                    server = instance
                    mutableState.value =
                        PhoneSetupState(
                            "waiting",
                            instance.url,
                            expiresAt = SystemClock.elapsedRealtime() + lifetimeMillis,
                        )
                    expiry =
                        scope.launch {
                            delay(lifetimeMillis)
                            if (run == generation) stop("expired")
                        }
                } catch (e: CancellationException) {
                    opening?.close()
                    throw e
                } catch (_: Exception) {
                    opening?.close()
                    if (run == generation)
                        mutableState.value =
                            PhoneSetupState(
                                "error",
                                error =
                                    "Connect the TV to your home Wi-Fi or Ethernet, then try again.",
                            )
                }
            }
    }

    fun approve() {
        if (mutableState.value.phase == "approval")
            mutableState.value = mutableState.value.copy(phase = "paired")
    }

    fun stop(phase: String = "closed") {
        generation++
        startJob?.cancel()
        startJob = null
        expiry?.cancel()
        expiry = null
        server?.close()
        server = null
        owner = ""
        draftToken = null
        discovered = emptyMap()
        loaded = false
        mutableState.value = PhoneSetupState(phase)
    }

    private suspend fun handle(run: Int, message: JSONObject): JSONObject =
        withContext(Dispatchers.Main.immediate) {
            lock.withLock {
                require(
                    run == generation &&
                        mutableState.value.phase !in listOf("closed", "done", "expired", "applying")
                ) {
                    "Setup session ended"
                }
                val client = message.getString("client")
                require(client.matches(Regex("[A-Za-z0-9_-]{20,64}")))
                val op = message.getString("op")
                if (op == "pair") {
                    if (owner.isBlank()) {
                        owner = client
                        val code = (100000 + SecureRandom().nextInt(900000)).toString()
                        mutableState.value =
                            mutableState.value.copy(phase = "approval", code = code)
                    }
                    require(client == owner) {
                        "Another phone is already pairing. Restart setup on the TV."
                    }
                    return@withLock JSONObject()
                        .put("ok", true)
                        .put("phase", mutableState.value.phase)
                        .put("code", mutableState.value.code)
                        .put(
                            "expiresIn",
                            (mutableState.value.expiresAt - SystemClock.elapsedRealtime())
                                .coerceAtLeast(0) / 1000,
                        )
                }
                require(client == owner && mutableState.value.phase == "paired") {
                    "Approve this phone on the TV first"
                }
                when (op) {
                    "info" -> info()
                    "connect" -> {
                        val url = normalizeServer(message.getString("server"))
                        val incoming = message.optString("token").trim()
                        require(incoming.length <= 8192)
                        val pendingCredential = draftToken.takeIf { url == draft.server }
                        require(
                            incoming.isNotEmpty() ||
                                pendingCredential != null ||
                                url == config().server
                        ) {
                            "Paste a token for the new Home Assistant server"
                        }
                        val credential =
                            incoming.ifBlank {
                                pendingCredential ?: if (url == config().server) token() else ""
                            }
                        require(credential.isNotBlank()) { "Paste your Home Assistant token" }
                        val states = fetch(url, credential)
                        require(run == generation) { "Setup session ended" }
                        discovered = states
                        draftToken = incoming.takeIf { it.isNotBlank() } ?: pendingCredential
                        val sameServer = url == draft.server
                        draft =
                            if (sameServer) draft.copy(server = url, demo = false)
                            else
                                draft.copy(
                                    server = url,
                                    demo = false,
                                    weather = "",
                                    controls = emptyList(),
                                    energy = emptyMap(),
                                    cameras = emptyList(),
                                )
                        loaded = true
                        info()
                    }
                    "entities" -> {
                        require(loaded) { "Connect Home Assistant first" }
                        val domain = message.optString("domain")
                        val query = message.optString("query").take(120)
                        val matches =
                            discovered.values
                                .filter {
                                    (if (domain == "controls")
                                        it.domain in
                                            listOf("light", "switch", "cover", "scene", "climate")
                                    else it.domain == domain) &&
                                        (domain != "sensor" ||
                                            it.attributes.optString("unit_of_measurement") in
                                                listOf("W", "kW", "MW")) &&
                                        (it.id.contains(query, true) ||
                                            it.name.contains(query, true))
                                }
                                .sortedBy { it.name }
                        JSONObject()
                            .put("ok", true)
                            .put("total", matches.size)
                            .put(
                                "entities",
                                JSONArray().apply {
                                    matches.take(200).forEach { put(entityJson(it)) }
                                },
                            )
                    }
                    "apply" -> {
                        val selection = parseConfig(message.getJSONObject("config"))
                        val installed = apps().map { it.id }.toSet()
                        require(
                            selection.apps.size <= 100 &&
                                selection.apps.map { it.id }.distinct().size ==
                                    selection.apps.size &&
                                selection.apps.all {
                                    it.id in installed &&
                                        it.label.length <= 80 &&
                                        it.icon.length <= 3
                                }
                        ) {
                            "An app is no longer installed. Reload setup."
                        }
                        var next =
                            draft.copy(
                                apps = selection.apps,
                                location = selection.location.take(80),
                                animate = selection.animate,
                                livePreviews = selection.livePreviews,
                                fahrenheit = selection.fahrenheit,
                                darkPanels = selection.darkPanels,
                                widgets = selection.widgets,
                            )
                        if (loaded) {
                            require(
                                selection.weather.isBlank() ||
                                    discovered[selection.weather]?.domain == "weather"
                            ) {
                                "Choose a weather entity"
                            }
                            require(
                                selection.controls.size <= 100 &&
                                    selection.controls.distinct().size == selection.controls.size &&
                                    selection.controls.all {
                                        discovered[it]?.domain in
                                            listOf("light", "switch", "scene", "climate", "cover")
                                    }
                            ) {
                                "Invalid device selection"
                            }
                            require(
                                selection.energy.size <= 6 &&
                                    selection.energy.all { (role, id) ->
                                        role in
                                            listOf(
                                                "solar",
                                                "battery",
                                                "grid",
                                                "heat_pump",
                                                "ev",
                                                "household",
                                            ) &&
                                            discovered[id]?.let {
                                                it.domain == "sensor" &&
                                                    it.attributes.optString(
                                                        "unit_of_measurement"
                                                    ) in listOf("W", "kW", "MW")
                                            } == true
                                    }
                            ) {
                                "Choose power sensors measured in W, kW or MW"
                            }
                            require(
                                selection.cameras.size <= 12 &&
                                    selection.cameras.map { it.entityId }.distinct().size ==
                                        selection.cameras.size &&
                                    selection.cameras.all {
                                        discovered[it.entityId]?.domain == "camera"
                                    }
                            ) {
                                "Invalid camera selection"
                            }
                            next =
                                next.copy(
                                    weather = selection.weather,
                                    controls = selection.controls,
                                    energy = selection.energy,
                                    cameras =
                                        selection.cameras.map {
                                            it.copy(
                                                hls = normalizeCameraEndpoint(it.hls),
                                                webRtc = normalizeCameraEndpoint(it.webRtc),
                                            )
                                        },
                                )
                        }
                        withContext(Dispatchers.Main.immediate) {
                            require(run == generation)
                            apply(next, draftToken)
                        }
                        mutableState.value = mutableState.value.copy(phase = "applying")
                        draftToken = null
                        JSONObject().put("ok", true)
                    }
                    else -> throw IllegalArgumentException("Unknown setup action")
                }
            }
        }

    private fun info(): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("config", draft.toJson())
            .put(
                "hasToken",
                draftToken != null || runCatching { token().isNotBlank() }.getOrDefault(false),
            )
            .put("connected", loaded)
            .put(
                "apps",
                JSONArray().apply {
                    apps().take(200).forEach {
                        put(JSONObject().put("id", it.id).put("name", it.name))
                    }
                },
            )
            .put(
                "selectedEntities",
                JSONArray().apply {
                    (draft.controls +
                            draft.weather +
                            draft.energy.values +
                            draft.cameras.map { it.entityId })
                        .distinct()
                        .mapNotNull { discovered[it] }
                        .forEach { put(entityJson(it)) }
                },
            )

    private fun entityJson(e: Entity) =
        JSONObject()
            .put("id", e.id)
            .put("name", e.name.take(120))
            .put("unit", e.attributes.optString("unit_of_measurement"))
            .put("available", e.available)
}

fun lanAddress(context: Context): InetAddress {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return manager.allNetworks
        .asSequence()
        .filter { network ->
            manager.getNetworkCapabilities(network)?.let {
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            } == true
        }
        .flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
        .map { it.address }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }
        ?: throw IllegalStateException("No LAN address")
}
