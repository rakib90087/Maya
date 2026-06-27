package com.example.ui

import android.content.Intent
import android.provider.Settings
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ChatMessage
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun AssistantScreen(
    viewModel: AssistantViewModel,
    onMicClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.chatMessages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val isPassiveListening by viewModel.isPassiveListening.collectAsState()
    val wakeWordModeEnabled by viewModel.wakeWordModeEnabled.collectAsState()

    var textInput by remember { mutableStateOf("") }
    var autoSpeakEnabled by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Auto scroll to bottom when messages list size changes
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header with AI Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MAYERA VOICE ASSISTANT",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val statusColor = if (isGenerating) AmberGold else if (isListening) SunsetRed else if (isPassiveListening) MaterialTheme.colorScheme.secondary else AquaGreen
                        val statusText = if (isGenerating) "Mayera is thinking..." else if (isListening) "Listening to you..." else if (isPassiveListening) "Waiting for 'Mayera'..." else "Mayera stands ready"
                        
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    statusColor,
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                    }
                }

                // Clear chat button
                IconButton(
                    onClick = { viewModel.clearChat() },
                    modifier = Modifier.testTag("clear_chat_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear Chat",
                        tint = MaterialTheme.colorScheme.onBackground.copy(0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Notification access warning panel
            val isNotificationAccessGranted by viewModel.isNotificationAccessGranted.collectAsState()
            val isOverlayPermissionGranted by viewModel.isOverlayPermissionGranted.collectAsState()
            val isBackgroundServiceRunning by viewModel.isBackgroundServiceRunning.collectAsState()
            val context = LocalContext.current
            
            if (!isNotificationAccessGranted) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clickable {
                            com.example.data.NotificationEvents.openNotificationAccessSettings(context)
                        }
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hearing,
                            contentDescription = "Notification access",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "নোটিফিকেশন ও কল রিডার চালু করুন",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "মায়াকে আপনার কল, হোয়াটসঅ্যাপ ও মেসেজ পড়ে শোনানোর জন্য এখানে ট্যাপ করে 'Notification Access' অনুমতি দিন।",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Always-on Overlay permission / Service controller card
            if (!isOverlayPermissionGranted) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clickable {
                            try {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                context.startActivity(intent)
                            }
                        }
                        .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Overlay permission",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ভাসমান বাবল অ্যাসিস্ট্যান্ট চালু করুন",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "অন্য অ্যাপের উপরে ড্র্যাগযোগ্য ভাসমান সহকারী ব্যবহার করতে এখানে ট্যাপ করে 'Display over other apps' পারমিশন দিন।",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isBackgroundServiceRunning) 
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) 
                        else 
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .border(
                            1.dp, 
                            if (isBackgroundServiceRunning) MaterialTheme.colorScheme.primary.copy(0.3f) else MaterialTheme.colorScheme.surfaceVariant.copy(0.5f), 
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isBackgroundServiceRunning) Icons.Default.CircleNotifications else Icons.Default.NotificationsNone,
                            contentDescription = "Background Service Status",
                            tint = if (isBackgroundServiceRunning) SunsetRed else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "সবসময় সচল ভাসমান অ্যাসিস্ট্যান্ট",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (isBackgroundServiceRunning) SunsetRed else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isBackgroundServiceRunning) 
                                    "মায়েরা অ্যাসিস্ট্যান্ট বাবল সচল এবং ব্যাকগ্রাউন্ডে রেডি আছে।" 
                                else 
                                    "অন্য যেকোনো অ্যাপ ব্যবহারের সময় মায়েরা বাবল এবং ভয়েস কল ডিটেকশন সক্রিয় করতে এটি অন করুন।",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isBackgroundServiceRunning,
                            onCheckedChange = { startService ->
                                val intent = Intent(context, com.example.data.MayeraForegroundService::class.java).apply {
                                    action = if (startService) 
                                        com.example.data.MayeraForegroundService.ACTION_START 
                                    else 
                                        com.example.data.MayeraForegroundService.ACTION_STOP
                                }
                                if (startService) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                } else {
                                    context.startService(intent)
                                }
                                viewModel.checkOverlayPermission(context)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SunsetRed,
                                checkedTrackColor = SunsetRed.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }

            // API key warning panel
            if (viewModel.isApiKeyMissing) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SunsetRed.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .border(1.dp, SunsetRed.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = SunsetRed,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "GEMINI API KEY MISSING",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = SunsetRed
                            )
                            Text(
                                text = "Set GEMINI_API_KEY in the AI Studio Secrets panel to connect to Mayera's AI mind.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Message Scroll area
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(messages) { msg ->
                    MessageBubble(message = msg)
                }
                item {
                    if (isGenerating) {
                        AiThinkingBubble()
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(80.dp)) // spacing for bottom bar
                }
            }

            // Bottom speak & input controls container
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 60.dp) // padding for navigation tabs
            ) {
                // Settings bar (TTS & Wake-on-Voice)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // TTS Toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (autoSpeakEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = null,
                            tint = if (autoSpeakEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (autoSpeakEnabled) "বাংলা TTS" else "Silent",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Switch(
                            checked = autoSpeakEnabled,
                            onCheckedChange = {
                                autoSpeakEnabled = it
                                if (!it) viewModel.stopSpeaking()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .scale(0.6f)
                                .testTag("tts_toggle_switch")
                        )
                    }

                    // Wake Word Toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hearing,
                            contentDescription = null,
                            tint = if (wakeWordModeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (wakeWordModeEnabled) "Wake: 'Mayera'" else "Wake: Off",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Switch(
                            checked = wakeWordModeEnabled,
                            onCheckedChange = {
                                viewModel.setWakeWordModeEnabled(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .scale(0.6f)
                                .testTag("wake_word_toggle_switch")
                        )
                    }
                }

                // Main controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Chat input bar
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("Speak or type task/message...", fontSize = 13.sp) },
                        maxLines = 3,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .testTag("assistant_chat_input"),
                        shape = RoundedCornerShape(26.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (textInput.isNotBlank()) {
                                viewModel.sendMessage(textInput)
                                textInput = ""
                                keyboardController?.hide()
                            }
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                        ),
                        trailingIcon = {
                            if (textInput.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        viewModel.sendMessage(textInput)
                                        textInput = ""
                                        keyboardController?.hide()
                                    },
                                    modifier = Modifier.testTag("send_message_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Send,
                                        contentDescription = "Send",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    )

                    // Futuristic Glowing Mic/Stop Button
                    GlowingMicButton(
                        isListening = isListening,
                        isSpeaking = isSpeaking,
                        onClick = onMicClick
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val bubbleColor = if (message.isUser) {
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    val alignment = if (message.isUser) Alignment.End else Alignment.Start
    val bubbleShape = if (message.isUser) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    val borderStroke = if (message.isUser) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(0.4f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(0.15f))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Card(
            shape = bubbleShape,
            border = borderStroke,
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            modifier = Modifier
                .widthIn(max = 290.dp)
                .testTag("chat_bubble_${if (message.isUser) "user" else "assistant"}")
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.text,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
fun AiThinkingBubble() {
    Row(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(0.15f), RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Mayera is analyzing",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        
        // Staggered pulsing dot animation
        val infiniteTransition = rememberInfiniteTransition(label = "dots")
        val dotAlpha1 by infiniteTransition.animateFloat(
            initialValue = 0.2f, targetValue = 1.0f,
            animationSpec = infiniteRepeatable(animation = tween(600, delayMillis = 0), repeatMode = RepeatMode.Reverse),
            label = "dot1"
        )
        val dotAlpha2 by infiniteTransition.animateFloat(
            initialValue = 0.2f, targetValue = 1.0f,
            animationSpec = infiniteRepeatable(animation = tween(600, delayMillis = 200), repeatMode = RepeatMode.Reverse),
            label = "dot2"
        )
        val dotAlpha3 by infiniteTransition.animateFloat(
            initialValue = 0.2f, targetValue = 1.0f,
            animationSpec = infiniteRepeatable(animation = tween(600, delayMillis = 400), repeatMode = RepeatMode.Reverse),
            label = "dot3"
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(modifier = Modifier.size(4.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha1), CircleShape))
            Box(modifier = Modifier.size(4.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha2), CircleShape))
            Box(modifier = Modifier.size(4.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha3), CircleShape))
        }
    }
}

@Composable
fun GlowingMicButton(
    isListening: Boolean,
    isSpeaking: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isListening) 1.25f else if (isSpeaking) 1.12f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val containerColor = if (isListening) {
        SunsetRed
    } else if (isSpeaking) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.primary
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(64.dp)
    ) {
        // Glowing Background Pulse Ring
        if (isListening || isSpeaking) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .scale(pulseScale)
                    .background(containerColor.copy(alpha = 0.25f), CircleShape)
            )
        }

        // Main Mic Circle Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(containerColor, containerColor.copy(alpha = 0.85f))
                    )
                )
                .clickable { onClick() }
                .testTag("glowing_mic_button")
        ) {
            Icon(
                imageVector = if (isListening) {
                    Icons.Default.Stop
                } else if (isSpeaking) {
                    Icons.Default.VolumeMute
                } else {
                    Icons.Default.Mic
                } ,
                contentDescription = if (isListening) "Stop Listening" else if (isSpeaking) "Mute Speech" else "Start Voice Search",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
