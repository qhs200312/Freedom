package com.v2ray.ang.ui.routing

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.routing.ClashYamlRules
import com.v2ray.ang.routing.ParsedYamlRules
import com.v2ray.ang.routing.RoutingRuleImportLoader
import com.v2ray.ang.routing.YamlImportFailure
import com.v2ray.ang.routing.YamlRuleImportException
import com.v2ray.ang.routing.YamlRuleNativeValidator
import com.v2ray.ang.routing.RustRuleParser
import com.v2ray.ang.routing.RoutingRuleLimits
import com.v2ray.ang.util.HttpUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class RoutingYamlImportState(
    val visible: Boolean = false,
    val input: String = "",
    val name: String = "",
    val sourceFile: String? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val parsed: ParsedYamlRules? = null,
    val error: YamlRuleImportException? = null,
)

class RoutingSettingsViewModel(application: Application) : BaseViewModel(application) {
    private val rulesets: MutableList<RulesetItem> = mutableListOf()

    private val _rulesetsFlow = MutableStateFlow<List<RulesetItem>>(emptyList())
    val rulesetsFlow: StateFlow<List<RulesetItem>> = _rulesetsFlow.asStateFlow()
    private val _nodesFlow = MutableStateFlow<List<RoutingNodeOption>>(emptyList())
    val nodesFlow = _nodesFlow.asStateFlow()
    private val _defaultNodeFlow = MutableStateFlow<String?>(null)
    val defaultNodeFlow = _defaultNodeFlow.asStateFlow()
    private val _yamlImport = MutableStateFlow(RoutingYamlImportState())
    internal val yamlImport = _yamlImport.asStateFlow()
    private var yamlImportJob: Job? = null
    private var reloadJob: Job? = null
    private val storageMutex = Mutex()

    internal fun openYamlImport() {
        _yamlImport.value = RoutingYamlImportState(visible = true)
    }

    internal fun closeYamlImport() {
        if (_yamlImport.value.saving) return
        yamlImportJob?.cancel()
        _yamlImport.value = RoutingYamlImportState()
    }

    internal fun updateYamlInput(value: String) {
        if (value.length > ClashYamlRules.MAX_PASTE_CHARS) {
            _yamlImport.value = _yamlImport.value.copy(
                parsed = null, error = YamlRuleImportException(YamlImportFailure.PASTE_TOO_LARGE),
            )
            return
        }
        _yamlImport.value = _yamlImport.value.copy(input = value, sourceFile = null, parsed = null, error = null)
    }

    internal fun updateYamlName(value: String) {
        _yamlImport.value = _yamlImport.value.copy(name = value.take(120))
    }

    internal fun previewYaml() {
        val input = _yamlImport.value.input
        loadYaml { file ->
            if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(input.trim())) {
                val url = RoutingRuleImportLoader.normalizeUrl(input)
                val port = SettingsManager.getHttpPort()
                    .takeIf { HttpUtil.isTcpPortOpen(AppConfig.LOOPBACK, it) } ?: 0
                val client = HttpUtil.buildOkHttpClient(
                    timeout = 15_000, httpPort = port, socksPort = 0,
                    proxyUsername = SettingsManager.getSocksUsername(),
                    proxyPassword = SettingsManager.getSocksPassword(),
                    followRedirects = true, requireHttps = true,
                ).newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
                RoutingRuleImportLoader.downloadToFile(url, client, file)
                url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
            } else {
                file.bufferedWriter(Charsets.UTF_8).use { it.write(input) }
                ""
            }
        }
    }

    internal fun loadYamlFile(uri: Uri) {
        if (!_yamlImport.value.visible) return
        _yamlImport.value = _yamlImport.value.copy(input = "", sourceFile = null)
        loadYaml(fromFile = true) { file ->
            val resolver = getApplication<Application>().contentResolver
            val filename = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }.orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }.substringBeforeLast('.')
            val coroutineContext = currentCoroutineContext()
            resolver.openInputStream(uri)?.let { input ->
                RoutingRuleImportLoader.copyToFile(input, file) { coroutineContext.ensureActive() }
            }
                ?: throw YamlRuleImportException(YamlImportFailure.READ)
            filename
        }
    }

    private fun loadYaml(fromFile: Boolean = false, read: suspend (File) -> String) {
        yamlImportJob?.cancel()
        _yamlImport.value = _yamlImport.value.copy(loading = true, parsed = null, error = null)
        yamlImportJob = viewModelScope.launch {
            try {
                val (parsed, suggestedName) = withContext(Dispatchers.IO) {
                    val file = File.createTempFile("routing-rules-", ".yaml", getApplication<Application>().cacheDir)
                    try {
                        val name = read(file)
                        RustRuleParser.parseFile(file, YamlRuleNativeValidator::regexIsValid) to name
                    } finally { file.delete() }
                }
                _yamlImport.value = _yamlImport.value.copy(
                    loading = false, parsed = parsed,
                    name = _yamlImport.value.name.ifBlank { suggestedName.take(120) },
                    sourceFile = suggestedName.takeIf { fromFile },
                )
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _yamlImport.value = _yamlImport.value.copy(
                    loading = false,
                    error = e as? YamlRuleImportException ?: YamlRuleImportException(YamlImportFailure.READ),
                )
            }
        }
    }

    internal fun saveYamlImport(outbound: String, profileId: String?) {
        val state = _yamlImport.value
        val parsed = state.parsed ?: return
        if (state.loading) return
        val name = state.name.trim().ifBlank { getApplication<Application>().getString(R.string.routing_yaml_default_name) }
        _yamlImport.value = state.copy(loading = true, saving = true)
        reloadJob?.cancel()
        yamlImportJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    storageMutex.withLock {
                        val imported = parsed.toRules(name, outbound, profileId)
                        val existing = MmkvManager.decodeRoutingRulesets().orEmpty()
                        (imported + existing).toMutableList().also {
                            RoutingRuleLimits.checkStoredRules(it)
                            MmkvManager.encodeRoutingRulesets(it)
                        }
                    }
                }
                replaceRules(loaded)
                _yamlImport.value = RoutingYamlImportState()
                toastSuccess(getApplication<Application>().getString(R.string.routing_yaml_imported, parsed.count))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _yamlImport.value = state.copy(error = e as? YamlRuleImportException
                    ?: YamlRuleImportException(YamlImportFailure.READ))
            }
        }
    }

    fun getAll(): List<RulesetItem> = rulesets.toList()

    fun reload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            val (loaded, nodes, selected) = withContext(Dispatchers.IO) {
                storageMutex.withLock {
                    val loaded = MmkvManager.decodeRoutingRulesets()?.toMutableList() ?: mutableListOf()
                    var changed = false
                    loaded.forEach { item ->
                        if (item.id.isEmpty()) { item.id = UUID.randomUUID().toString(); changed = true }
                    }
                    if (changed) MmkvManager.encodeRoutingRulesets(loaded)
                    Triple(loaded, loadRoutingNodeOptions(getApplication()),
                        MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)?.remarks)
                }
            }
            _nodesFlow.value = nodes
            _defaultNodeFlow.value = selected
            replaceRules(loaded)
        }
    }

    private fun replaceRules(loaded: List<RulesetItem>) {
        rulesets.clear()
        rulesets.addAll(loaded)
        _rulesetsFlow.value = rulesets.toList()
    }

    fun update(position: Int, item: RulesetItem) {
        if (position in rulesets.indices) {
            reloadJob?.cancel()
            rulesets[position] = item
            _rulesetsFlow.value = rulesets.toList()
            persistRules()
        }
    }

    fun move(fromPosition: Int, toPosition: Int) {
        if (rulesets.moveItem(fromPosition, toPosition)) {
            reloadJob?.cancel()
            _rulesetsFlow.value = rulesets.toList()
            persistRules()
        }
    }

    private fun persistRules() {
        val snapshot = rulesets.toMutableList()
        viewModelScope.launch {
            storageMutex.withLock {
                withContext(Dispatchers.IO) { MmkvManager.encodeRoutingRulesets(snapshot) }
            }
        }
    }
}
