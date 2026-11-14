package com.example.gemmachat

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.gemmachat.ui.theme.GemmaChatTheme
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MODEL_TAG = "Gemma"
private const val MODEL_RELATIVE_PATH =
    "models/gemma-4-E2B-it/gemma-4-E2B-it.litertlm"

class MainActivity : ComponentActivity() {
    private var modelStatus by mutableStateOf(ModelStatus.CHECKING)
    private var statusMessage by mutableStateOf("Checking for the on-device model...")
    private var prompt by mutableStateOf("")
    private var isGenerating by mutableStateOf(false)
    private val messages = mutableStateListOf<ChatMessage>()

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GemmaChatTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ChatScreen(
                        modelStatus = modelStatus,
                        statusMessage = statusMessage,
                        messages = messages,
                        prompt = prompt,
                        isGenerating = isGenerating,
                        onPromptChange = { prompt = it },
                        onSend = ::sendMessage,
                        onRetry = ::loadModel,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }

        loadModel()
    }

    private fun checkModel(): File? {
        val modelFile = File(filesDir, MODEL_RELATIVE_PATH)
        val isAvailable = modelFile.isFile && modelFile.canRead() && modelFile.length() > 0L

        Log.d(
            MODEL_TAG,
            "Model available: $isAvailable, size: ${modelFile.length()} bytes, " +
                "path: ${modelFile.absolutePath}"
        )
        if (!isAvailable) {
            modelStatus = ModelStatus.MISSING
            statusMessage = "Model file is missing, empty, or unreadable: ${modelFile.absolutePath}"
            return null
        }
        return modelFile
    }

    private fun loadModel() {
        if (modelStatus == ModelStatus.LOADING) return
        val modelFile = checkModel() ?: return

        modelStatus = ModelStatus.LOADING
        statusMessage = "Loading model on the device. This can take several seconds..."
        lifecycleScope.launch {
            try {
                val (loadedEngine, loadedConversation) = withContext(Dispatchers.IO) {
                    val newEngine = Engine(
                        EngineConfig(
                            modelPath = modelFile.absolutePath,
                            backend = Backend.CPU(),
                            cacheDir = cacheDir.absolutePath
                        )
                    )
                    try {
                        newEngine.initialize()
                        val newConversation = newEngine.createConversation(
                            ConversationConfig(
                                systemInstruction = Contents.of(
                                    "You are a helpful assistant. Keep answers concise."
                                )
                            )
                        )
                        newEngine to newConversation
                    } catch (exception: Exception) {
                        try {
                            newEngine.close()
                        } catch (closeException: Exception) {
                            Log.e(
                                MODEL_TAG,
                                "Failed to close engine after initialization error",
                                closeException
                            )
                        }
                        throw exception
                    }
                }

                engine = loadedEngine
                conversation = loadedConversation
                modelStatus = ModelStatus.READY
                statusMessage = "Model loaded and ready for a test prompt."
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(MODEL_TAG, "Failed to initialize on-device model", exception)
                modelStatus = ModelStatus.FAILED
                statusMessage = exception.localizedMessage
                    ?: "Model initialization failed. Check the device log for details."
            }
        }
    }

    private fun sendMessage() {
        val messageText = prompt.trim()
        val activeConversation = conversation ?: return
        if (messageText.isEmpty() || isGenerating || modelStatus != ModelStatus.READY) return

        prompt = ""
        messages.add(ChatMessage(isUser = true, text = messageText))
        val responseIndex = messages.size
        messages.add(ChatMessage(isUser = false, text = ""))
        isGenerating = true

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    activeConversation.sendMessageAsync(messageText).collect { response ->
                        val chunk = response.toString()
                        if (chunk.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                val previous = messages[responseIndex]
                                messages[responseIndex] = previous.copy(text = previous.text + chunk)
                            }
                        }
                    }
                }
                if (messages[responseIndex].text.isBlank()) {
                    messages[responseIndex] = messages[responseIndex].copy(
                        text = "The model returned an empty response."
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(MODEL_TAG, "Model inference failed", exception)
                val previous = messages[responseIndex]
                val errorText = exception.localizedMessage ?: "Unknown inference error."
                messages[responseIndex] = previous.copy(
                    text = if (previous.text.isBlank()) {
                        "Inference failed: $errorText"
                    } else {
                        "${previous.text}\n\nGeneration stopped: $errorText"
                    }
                )
            } finally {
                isGenerating = false
            }
        }
    }

    override fun onDestroy() {
        try {
            conversation?.close()
        } catch (exception: Exception) {
            Log.e(MODEL_TAG, "Failed to close model conversation", exception)
        }
        try {
            engine?.close()
        } catch (exception: Exception) {
            Log.e(MODEL_TAG, "Failed to close model engine", exception)
        }
        conversation = null
        engine = null
        super.onDestroy()
    }
}

private enum class ModelStatus {
    CHECKING,
    MISSING,
    LOADING,
    READY,
    FAILED
}

private data class ChatMessage(
    val isUser: Boolean,
    val text: String
)

@Composable
private fun ChatScreen(
    modelStatus: ModelStatus,
    statusMessage: String,
    messages: List<ChatMessage>,
    prompt: String,
    isGenerating: Boolean,
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        modifier = modifier
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "Gemma on-device test",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(12.dp))
        ModelStatusCard(
            status = modelStatus,
            message = statusMessage,
            onRetry = onRetry
        )
        Spacer(Modifier.height(12.dp))

        if (messages.isEmpty()) {
            Text(
                text = "Send a prompt to verify that the model can generate a response on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { message ->
                MessageBubble(message)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier.weight(1f),
                enabled = modelStatus == ModelStatus.READY && !isGenerating,
                placeholder = { Text("Ask Gemma something...") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onSend,
                enabled = modelStatus == ModelStatus.READY &&
                    !isGenerating &&
                    prompt.isNotBlank(),
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun ModelStatusCard(
    status: ModelStatus,
    message: String,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                ModelStatus.READY -> MaterialTheme.colorScheme.primaryContainer
                ModelStatus.FAILED, ModelStatus.MISSING -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (status) {
                        ModelStatus.CHECKING -> "Checking model"
                        ModelStatus.MISSING -> "Model not found"
                        ModelStatus.LOADING -> "Loading model"
                        ModelStatus.READY -> "Model ready"
                        ModelStatus.FAILED -> "Model failed to load"
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                if (status == ModelStatus.CHECKING || status == ModelStatus.LOADING) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            Text(text = message, style = MaterialTheme.typography.bodySmall)
            if (status == ModelStatus.MISSING || status == ModelStatus.FAILED) {
                Text(
                    text = "Expected file: ${File(MODEL_RELATIVE_PATH).path}",
                    style = MaterialTheme.typography.labelSmall
                )
                Button(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) {
            Arrangement.End
        } else {
            Arrangement.Start
        }
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (message.isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (message.isUser) "You" else "Gemma",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message.text.ifEmpty { "Generating..." },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
