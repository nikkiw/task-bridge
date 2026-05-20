/*
 * Copyright 2026 Nikolay Vlasov (https://github.com/nikkiw)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ndev.android.taskbridge.sample

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nikkiw.taskbridge.api.TaskBridgeClient
import io.github.nikkiw.taskbridge.api.TaskBridgeConfig
import io.github.nikkiw.taskbridge.api.observeTaskEvents
import io.github.nikkiw.taskbridge.api.startTaskJson
import io.github.nikkiw.taskbridge.api.submitAction
import io.github.nikkiw.taskbridge.checkpoint.DataStoreTaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.model.RawTaskEventEnvelope
import io.github.nikkiw.taskbridge.model.TaskActionAcceptedEvent
import io.github.nikkiw.taskbridge.model.TaskActionRequest
import io.github.nikkiw.taskbridge.model.TaskCancelledEvent
import io.github.nikkiw.taskbridge.model.TaskCompletedEvent
import io.github.nikkiw.taskbridge.model.TaskCreateJsonRequest
import io.github.nikkiw.taskbridge.model.TaskEvent
import io.github.nikkiw.taskbridge.model.TaskEventType
import io.github.nikkiw.taskbridge.model.TaskFailedEvent
import io.github.nikkiw.taskbridge.model.TaskMessageEvent
import io.github.nikkiw.taskbridge.model.TaskProgressEvent
import io.github.nikkiw.taskbridge.model.TaskStartedEvent
import io.github.nikkiw.taskbridge.model.TaskSuspendedEvent
import io.github.nikkiw.taskbridge.model.UnknownTaskEvent
import io.github.nikkiw.taskbridge.model.toTaskEvent
import io.github.nikkiw.taskbridge.okhttp.OkHttpTaskBridgeTransportConfig
import io.github.nikkiw.taskbridge.okhttp.OkHttpTaskBridgeTransportFactory
import io.github.nikkiw.taskbridge.transport.taskBridgeJson
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.File

enum class SampleDestination {
    HOME,
    MINIMAL_GREETER,
    ADVANCED_ENTERPRISE,
}

/**
 * AndroidViewModel survives configuration changes (rotation).
 * The underlying SharedPreferences store survives process death.
 */
class TaskBridgeViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("taskbridge_demo", Context.MODE_PRIVATE)

    var currentDestination by mutableStateOf(
        SampleDestination.valueOf(prefs.getString("current_destination", SampleDestination.HOME.name)!!),
    )
        private set

    var baseUrl by mutableStateOf(prefs.getString("base_url", "http://10.0.2.2:8000")!!)
        private set

    var greeterName by mutableStateOf(prefs.getString("greeter_name", "Android Developer")!!)
        private set

    var isLoggedId by mutableStateOf(prefs.getBoolean("is_logged_in", false))
        private set

    var activeTaskId by mutableStateOf(prefs.getString("active_task_id", null))
        private set

    var statusLines by mutableStateOf<List<String>>(
        prefs.getStringSet("status_lines", emptySet())?.toList()?.sortedBy { it.takeWhile { c -> c.isDigit() }.toLongOrNull() ?: 0L } ?: emptyList(),
    )

    var currentSuspension by mutableStateOf<TaskSuspendedEvent?>(
        prefs.getString("current_suspension_json", null)?.let { json ->
            try {
                taskBridgeJson().decodeFromString<RawTaskEventEnvelope>(json).toTaskEvent() as? TaskSuspendedEvent
            } catch (_: Exception) {
                null
            }
        },
    )

    var progress by mutableStateOf(prefs.getFloat("progress", 0f))

    // To allow user to see the final result, we don't clear taskId from UI immediately.
    // Instead, we mark it as "finished" to stop background observation.
    var isFinished by mutableStateOf(prefs.getBoolean("is_finished", false))
        private set

    fun navigate(destination: SampleDestination) {
        currentDestination = destination
        prefs.edit().putString("current_destination", destination.name).apply()
    }

    fun updateBaseUrl(url: String) {
        baseUrl = url
        prefs.edit().putString("base_url", url).apply()
    }

    fun updateGreeterName(name: String) {
        greeterName = name
        prefs.edit().putString("greeter_name", name).apply()
    }

    fun login() {
        isLoggedId = true
        prefs.edit().putBoolean("is_logged_in", true).apply()
    }

    fun logout() {
        isLoggedId = false
        prefs.edit().putBoolean("is_logged_in", false).apply()
        clear()
    }

    fun updateActiveTask(id: String?) {
        activeTaskId = id
        isFinished = false
        prefs.edit()
            .putString("active_task_id", id)
            .putBoolean("is_finished", false)
            .apply()

        if (id == null) {
            prefs.edit()
                .remove("progress")
                .remove("status_lines")
                .remove("current_suspension_json")
                .apply()
            currentSuspension = null
            progress = 0f
            statusLines = emptyList()
        }
    }

    fun handleEvent(ev: TaskEvent) {
        val line = ev.toSampleLine()
        if (statusLines.none { it == line }) {
            statusLines = (statusLines + line).takeLast(50) // Keep last 50 for demo
            prefs.edit().putStringSet("status_lines", statusLines.toSet()).apply()
        }

        when (ev) {
            is TaskProgressEvent -> {
                progress = (ev.payload["progress"]?.toString()?.toIntOrNull() ?: 0) / 100f
                prefs.edit().putFloat("progress", progress).apply()
            }
            is TaskSuspendedEvent -> {
                currentSuspension = ev
                val envelope = RawTaskEventEnvelope(
                    type = TaskEventType.TASK_SUSPENDED.name,
                    taskId = ev.taskId,
                    eventId = ev.eventId,
                    createdAt = ev.createdAt,
                    payload = ev.payload,
                )
                prefs.edit().putString("current_suspension_json", taskBridgeJson().encodeToString(envelope)).apply()
            }
            is TaskActionAcceptedEvent -> {
                currentSuspension = null
                prefs.edit().remove("current_suspension_json").apply()
            }
            is TaskCompletedEvent, is TaskFailedEvent, is TaskCancelledEvent -> {
                // Mark as finished but KEEP taskId so user can read the logs
                isFinished = true
                currentSuspension = null
                // We clear it from persistent storage so it doesn't resume on next app start,
                // but keep it in RAM (activeTaskId) for current screen.
                prefs.edit()
                    .remove("active_task_id")
                    .remove("current_suspension_json")
                    .putBoolean("is_finished", true)
                    .apply()
            }
            else -> {}
        }
    }

    fun clear() {
        updateActiveTask(null)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainApp()
                }
            }
        }
    }
}

@Composable
fun MainApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: TaskBridgeViewModel = viewModel()

    val client = remember(viewModel.baseUrl) {
        TaskBridgeClient.create(
            TaskBridgeConfig(
                baseUrl = viewModel.baseUrl.trim(),
                transportFactory = OkHttpTaskBridgeTransportFactory<Unit>(
                    OkHttpTaskBridgeTransportConfig(okHttpClient = OkHttpClient()),
                ),
                authHeaderProvider = { _, _ -> "Bearer mock-firebase-token" },
                // Persistent checkpoint store: saves lastEventId to disk.
                checkpointStore = DataStoreTaskBridgeCheckpointStore(
                    file = File(context.filesDir, "taskbridge_checkpoints.preferences_pb"),
                    scope = scope,
                ),
                retryGate = io.github.nikkiw.taskbridge.policy.AndroidConnectivityRetryGate(context),
            ),
        )
    }

    // Global background observation: maintains connection during screen transitions
    LaunchedEffect(viewModel.activeTaskId, viewModel.isFinished) {
        val taskId = viewModel.activeTaskId ?: return@LaunchedEffect
        if (viewModel.isFinished) return@LaunchedEffect

        client.observeTaskEvents(taskId)
            .catch { e -> viewModel.handleEvent(localErrorEvent(taskId, e.message ?: "Unknown error")) }
            .collect { ev ->
                viewModel.handleEvent(ev)
            }
    }

    when (viewModel.currentDestination) {
        SampleDestination.HOME -> WelcomeScreen(
            baseUrl = viewModel.baseUrl,
            appState = viewModel,
            onBaseUrlChange = { viewModel.updateBaseUrl(it) },
            onNavigate = { viewModel.navigate(it) },
        )
        SampleDestination.MINIMAL_GREETER -> MinimalGreeterScreen(
            client = client,
            appState = viewModel,
            onBack = { viewModel.navigate(SampleDestination.HOME) },
        )
        SampleDestination.ADVANCED_ENTERPRISE -> AdvancedEnterpriseScreen(
            client = client,
            appState = viewModel,
            onBack = { viewModel.navigate(SampleDestination.HOME) },
        )
    }
}

@Composable
fun WelcomeScreen(
    baseUrl: String,
    appState: TaskBridgeViewModel,
    onBaseUrlChange: (String) -> Unit,
    onNavigate: (SampleDestination) -> Unit,
) {
    val isOnlineState = rememberIsOnline()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("TaskBridge Sample App", style = MaterialTheme.typography.headlineMedium)

        appState.activeTaskId?.let { id ->
            Card(
                onClick = { onNavigate(SampleDestination.ADVANCED_ENTERPRISE) },
                colors = CardDefaults.cardColors(
                    containerColor = if (appState.isFinished) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (appState.isFinished) "Task Result Ready" else "Active Task Detected", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(if (appState.isFinished) "VIEW →" else "RESUME →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("ID: $id", style = MaterialTheme.typography.bodySmall)

                    if (appState.currentSuspension != null) {
                        Spacer(Modifier.height(4.dp))
                        Text("⚠️ ACTION REQUIRED", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    }

                    ConnectivityOfflineBadge(
                        isOnlineProvider = { !isOnlineState.value && !appState.isFinished }
                    )
                }
            }
        }

        Text("Select a demo to explore common use cases.")

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            label = { Text("Base URL (FastAPI Host)") },
            singleLine = true,
        )

        Spacer(Modifier.height(8.dp))

        DemoCard(
            title = "01. Minimal Greeter",
            description = "Basic JSON task with progress. Best for zero-infrastructure start.",
            onClick = { onNavigate(SampleDestination.MINIMAL_GREETER) },
        )

        DemoCard(
            title = "02. Advanced Enterprise",
            description = "Firebase auth, Temporal worker, and Human-in-the-loop actions.",
            onClick = { onNavigate(SampleDestination.ADVANCED_ENTERPRISE) },
        )
    }
}

@Composable
fun DemoCard(
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun AdvancedEnterpriseScreen(
    client: TaskBridgeClient<Unit>,
    appState: TaskBridgeViewModel,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isOnlineState = rememberIsOnline()
    // Local state to track if we want to show the "Start New" form even if a task is active
    var showNewTaskForm by remember { mutableStateOf(appState.activeTaskId == null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(16.dp))
            Text("Advanced Enterprise", style = MaterialTheme.typography.titleLarge)
        }

        if (!appState.isLoggedId) {
            EnterpriseLoginMock(onLogin = {
                appState.login()
                showNewTaskForm = true
            })
        } else {
            // Header with user info and logout
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("John Doe", fontWeight = FontWeight.Bold)
                        Text("Enterprise Account", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { appState.logout() }) { Text("Logout") }
                }
            }

            // Task Selection / New Task Flow
            if (appState.activeTaskId != null && !showNewTaskForm) {
                // Showing ACTIVE or FINISHED task
                Card(
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (appState.isFinished) "Analysis Complete" else "Task In Progress",
                            fontWeight = FontWeight.Bold,
                            color = if (appState.isFinished) Color(0xFF1976D2) else Color(0xFF2E7D32),
                        )
                        Text("ID: ${appState.activeTaskId}", style = MaterialTheme.typography.bodySmall)

                        LinearProgressIndicator(
                            progress = if (appState.isFinished) 1f else appState.progress,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        appState.currentSuspension?.let { suspension ->
                            SuspensionActionCard(
                                suspension = suspension,
                                onAction = { actionType ->
                                    scope.launch {
                                        try {
                                            client.submitAction(
                                                appState.activeTaskId!!,
                                                TaskActionRequest(
                                                    clientActionId = "act-${System.currentTimeMillis()}",
                                                    suspendId = suspension.suspension.suspendId,
                                                    actionType = actionType,
                                                    payload = buildJsonObject { put("reason", "Manual user decision") },
                                                ),
                                            )
                                        } catch (e: Exception) {
                                            appState.handleEvent(localErrorEvent(appState.activeTaskId!!, "Action failed: ${e.message}"))
                                        }
                                    }
                                },
                            )
                        }

                        ConnectivityOfflineBadge(
                            isOnlineProvider = { !isOnlineState.value && !appState.isFinished }
                        )

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showNewTaskForm = true },
                            colors = ButtonDefaults.outlinedButtonColors(),
                        ) {
                            Text(if (appState.isFinished) "New Analysis" else "Discard & Start New")
                        }
                    }
                }
            } else {
                // Showing START NEW form
                Card(
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("New Smart Analysis", fontWeight = FontWeight.Bold)
                        Text("Submit document for Temporal-powered automated review.", style = MaterialTheme.typography.bodySmall)

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    appState.clear()
                                    showNewTaskForm = false
                                    appState.handleEvent(localInfoEvent("info", "Initiating secure document analysis..."))
                                    try {
                                        val result = client.startTaskJson(
                                            TaskCreateJsonRequest(
                                                clientRequestId = "ent-${System.currentTimeMillis()}",
                                                taskType = "enterprise.document.review",
                                                input = buildJsonObject { put("doc_id", "DOC-123") },
                                            ),
                                        )
                                        appState.updateActiveTask(result.taskId)
                                    } catch (e: Exception) {
                                        appState.handleEvent(localErrorEvent("error", "Failed: ${e.message}"))
                                        showNewTaskForm = true
                                    }
                                }
                            },
                        ) {
                            Text("Start Analysis (DOC-123)")
                        }

                        if (appState.activeTaskId != null) {
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { showNewTaskForm = false },
                            ) {
                                Text("Return to Results")
                            }
                        }
                    }
                }
            }

            // Event Logs
            if (appState.statusLines.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Session Events:", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { appState.clear() }) { Text("Clear Log") }
                }
                appState.statusLines.reversed().forEach { line ->
                    val color = if (line.contains("Error", ignoreCase = true) ||
                        line.contains("Failed", ignoreCase = true)
                    ) {
                        MaterialTheme.colorScheme.error
                    } else if (line.contains("Completed", ignoreCase = true)) {
                        Color(0xFF1976D2)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    Text(line, style = MaterialTheme.typography.bodySmall, color = color)
                }
            }
        }
    }
}

@Composable
fun EnterpriseLoginMock(onLogin: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Authentication Required", style = MaterialTheme.typography.titleMedium)
        Text("This scenario simulates a Firebase-authenticated session.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onLogin) {
            Text("Login as John Doe")
        }
    }
}

@Composable
fun SuspensionActionCard(suspension: TaskSuspendedEvent, onAction: (String) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ACTION REQUIRED", fontWeight = FontWeight.ExtraBold)

            val message = suspension.payload["message"]?.toString()?.removeSurrounding("\"") ?: "Action required"
            Text(message)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suspension.suspension.allowedActions.forEach { action ->
                    Button(onClick = { onAction(action) }) {
                        Text(action.uppercase())
                    }
                }
            }
        }
    }
}

@Composable
fun MinimalGreeterScreen(
    client: TaskBridgeClient<Unit>,
    appState: TaskBridgeViewModel,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(16.dp))
            Text("Minimal Greeter", style = MaterialTheme.typography.titleLarge)
        }

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = appState.greeterName,
            onValueChange = { appState.updateGreeterName(it) },
            label = { Text("Enter your name") },
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = appState.activeTaskId == null || appState.isFinished,
            onClick = {
                scope.launch {
                    appState.clear()
                    appState.handleEvent(localInfoEvent("info", "Starting greeter task..."))
                    try {
                        val result = client.startTaskJson(
                            TaskCreateJsonRequest(
                                clientRequestId = "greet-${System.currentTimeMillis()}",
                                taskType = "greeter",
                                input = buildJsonObject { put("name", appState.greeterName) },
                            ),
                        )
                        appState.updateActiveTask(result.taskId)
                    } catch (e: Exception) {
                        appState.handleEvent(localErrorEvent("error", "Failed to start: ${e.message}"))
                    }
                }
            },
        ) {
            Text("Greet Me!")
        }

        Text("Event Log:", fontWeight = FontWeight.Bold)
        appState.statusLines.forEach { line ->
            val color = if (line.contains("Error", ignoreCase = true) ||
                line.contains("Failed", ignoreCase = true)
            ) {
                MaterialTheme.colorScheme.error
            } else if (line.contains("Completed", ignoreCase = true)) {
                Color(0xFF1976D2)
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            Text(line, color = color)
        }
    }
}

private fun localErrorEvent(taskId: String, message: String): UnknownTaskEvent = UnknownTaskEvent(
    wireType = "error",
    taskId = taskId,
    eventId = "local-err-${System.currentTimeMillis()}",
    createdAt = System.currentTimeMillis().toString(),
    payload = buildJsonObject { put("message", message) },
)

private fun localInfoEvent(taskId: String, message: String): TaskMessageEvent = TaskMessageEvent(
    taskId = taskId,
    eventId = "local-info-${System.currentTimeMillis()}",
    createdAt = System.currentTimeMillis().toString(),
    payload = buildJsonObject { put("message", message) },
)

private fun TaskEvent.toSampleLine(): String = when (this) {
    is TaskStartedEvent -> "[Started] $eventId"
    is TaskProgressEvent -> "[Progress] ${payload["progress"]}%: ${payload["message"]}"
    is TaskMessageEvent -> "[Message] ${payload["message"]}"
    is TaskSuspendedEvent -> "[Suspended] ID=$eventId Actions=${payload["allowedActions"]}"
    is TaskActionAcceptedEvent -> "[ActionAccepted] $eventId"
    is TaskCompletedEvent -> "[Completed] Result: ${payload["message"]}"
    is TaskFailedEvent -> "[Failed] Reason: ${payload["message"]}"
    is TaskCancelledEvent -> "[Cancelled]"
    is UnknownTaskEvent -> "[Unknown] $wireType: ${payload["message"] ?: ""}"
}

@Composable
fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    
    val initialOnline = remember(appContext) {
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
        capabilities?.let {
            it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: false
    }

    return produceState(initialValue = initialOnline, appContext) {
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        
        fun checkOnline(): Boolean {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                   capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { value = checkOnline() }
            override fun onLost(network: android.net.Network) { value = checkOnline() }
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
                value = checkOnline()
            }
        }
        
        connectivityManager.registerDefaultNetworkCallback(callback)
        
        awaitDispose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }
}

@Composable
fun ConnectivityOfflineBadge(
    isOnlineProvider: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val isOnline = isOnlineProvider()
    if (!isOnline) {
        val context = LocalContext.current
        
        val infiniteTransition = rememberInfiniteTransition(label = "offlinePulse")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = EaseInOutCubic),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
        
        Spacer(Modifier.height(8.dp))
        Surface(
            onClick = {
                val intent = android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
                context.startActivity(intent)
            },
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
            shape = MaterialTheme.shapes.small,
            modifier = modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha),
                            shape = CircleShape
                        )
                )
                
                Text(
                    text = "⚠️ Network offline. Reconnecting...",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                
                Spacer(Modifier.weight(1f))
                
                Text(
                    text = "Settings →",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

