package dev.hatv.launcher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hatv.launcher.data.*
import dev.hatv.launcher.ha.HomeAssistantClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    val store = SettingsStore(app)
    private val mutableConfig = MutableStateFlow(store.load())
    val config = mutableConfig.asStateFlow()
    val ha = HomeAssistantClient(viewModelScope)
    private val demoStates = MutableStateFlow(Demo.entities)
    val entities =
        combine(config, ha.states, demoStates) { c, live, demo -> if (c.demo) demo else live }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                if (config.value.demo) Demo.entities else emptyMap(),
            )
    val dashboardEntities =
        combine(config, entities) { c, all ->
                if (c.demo) all
                else {
                    val selected =
                        c.controls.toSet() +
                            c.weather +
                            c.energy.values +
                            c.cameras.map { it.entityId }
                    all.filterKeys { it in selected }
                }
            }
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                if (config.value.demo) Demo.entities else emptyMap(),
            )
    val message = MutableStateFlow<String?>(null)
    val homeRequest = MutableStateFlow(0)

    fun requestHome() {
        homeRequest.value += 1
    }

    val serverTemperatureF = MutableStateFlow(false)
    val hourly = MutableStateFlow<List<Forecast>>(emptyList())
    val daily = MutableStateFlow<List<Forecast>>(emptyList())
    val installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val catalog = AppCatalog(app)
    private var visible = false
    private var forecastJob: Job? = null

    init {
        viewModelScope.launch {
            ha.status.collectLatest { status ->
                forecastJob?.cancel()
                if (status == HomeAssistantClient.Status.ONLINE)
                    forecastJob = launch {
                        try {
                            val reply = ha.command("get_config")
                            serverTemperatureF.value =
                                reply
                                    .optJSONObject("result")
                                    ?.optJSONObject("unit_system")
                                    ?.optString("temperature") == "°F"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            serverTemperatureF.value = false
                        }
                        while (isActive) {
                            refreshForecast()
                            delay(30 * 60_000L)
                        }
                    }
            }
        }
    }

    fun resume() {
        visible = true
        viewModelScope.launch(Dispatchers.IO) { installed.value = catalog.discover() }
        connect()
    }

    fun pause() {
        visible = false
        ha.disconnect()
        forecastJob?.cancel()
    }

    private fun connect() {
        ha.disconnect()
        if (config.value.demo) {
            hourly.value = Demo.hourly
            daily.value = Demo.daily
            return
        }
        if (!visible || config.value.server.isBlank()) return
        runCatching { store.token() }
            .onSuccess { token ->
                if (token.isNotBlank())
                    runCatching { ha.connect(config.value.server, token) }
                        .onFailure { message.value = it.message }
            }
            .onFailure {
                message.value = "Saved token cannot be decrypted. Forget it and enter a new token."
            }
    }

    fun save(c: Config, token: String = "") = action {
        val normalized = if (c.server.isBlank()) "" else normalizeServer(c.server)
        require(c.demo || normalized.isNotBlank()) { "Enter a Home Assistant URL" }
        require(
            normalized == config.value.server || store.token().isBlank() || token.isNotBlank()
        ) {
            "Re-enter the token when changing the server URL"
        }
        if (token.isNotBlank()) store.saveToken(token.trim())
        require(c.demo || store.token().isNotBlank()) { "Enter an access token" }
        val next =
            c.copy(
                server = normalized,
                cameras =
                    c.cameras.map {
                        it.copy(
                            hls = normalizeCameraEndpoint(it.hls),
                            webRtc = normalizeCameraEndpoint(it.webRtc),
                        )
                    },
            )
        store.save(next)
        mutableConfig.value = next
        hourly.value = emptyList()
        daily.value = emptyList()
        connect()
        message.value = "✓"
    }

    fun update(c: Config) {
        store.save(c)
        mutableConfig.value = c
        viewModelScope.launch { refreshForecast() }
    }

    fun test(server: String, token: String) = action {
        require(token.isNotBlank() || normalizeServer(server) == config.value.server) {
            "Enter a token to test a different server"
        }
        ha.test(server, token.ifBlank { store.token() })
        message.value = "✓ Connection successful"
    }

    fun forgetToken() {
        store.forgetToken()
        ha.disconnect()
        message.value = "✓"
    }

    fun launchApp(id: String) {
        val app = installed.value.find { it.id == id }
        if (app == null || !catalog.launch(app)) message.value = "App unavailable"
    }

    fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = e.message ?: "Action failed"
            }
        }
    }

    fun service(entity: Entity, service: String, data: JSONObject = JSONObject()) = action {
        require(entity.available) { "Entity unavailable" }
        if (config.value.demo) {
            val next =
                when (service) {
                    "toggle" -> if (entity.state == "on") "off" else "on"
                    "open_cover" -> "open"
                    "close_cover" -> "closed"
                    else -> entity.state
                }
            val attrs = JSONObject(entity.attributes.toString())
            if (data.has("temperature")) attrs.put("temperature", data.getDouble("temperature"))
            demoStates.value =
                demoStates.value + (entity.id to entity.copy(state = next, attributes = attrs))
            if (entity.domain == "scene") message.value = "✓ ${entity.name}"
        } else {
            require(ha.status.value == HomeAssistantClient.Status.ONLINE) {
                "Home Assistant is offline"
            }
            ha.service(entity.domain, service, entity.id, data)
        }
    }

    suspend fun refreshForecast() {
        if (config.value.demo) return
        val weather = config.value.weather.takeIf { it.isNotBlank() } ?: return
        hourly.value = runCatching { ha.forecasts(weather, "hourly") }.getOrDefault(emptyList())
        daily.value = runCatching { ha.forecasts(weather, "daily") }.getOrDefault(emptyList())
    }

    override fun onCleared() {
        ha.disconnect()
    }
}
