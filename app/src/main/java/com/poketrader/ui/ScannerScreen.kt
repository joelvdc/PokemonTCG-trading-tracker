package com.poketrader.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.poketrader.AppContainer
import com.poketrader.container
import com.poketrader.data.AddResult
import com.poketrader.data.CardRef
import com.poketrader.data.CardTarget
import com.poketrader.data.ImageKey
import com.poketrader.data.PriceSet
import com.poketrader.data.PriceType
import com.poketrader.data.TcgCard
import com.poketrader.scan.CardRecognizer
import com.poketrader.scan.CardTextAnalyzer
import com.poketrader.scan.ScanClues
import com.poketrader.scan.ScanGuide
import com.poketrader.scan.ScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import androidx.compose.ui.geometry.Size as GSize

data class ScannedEntry(
    val card: CardRef,
    val language: String,
    val result: AddResult,
    /** Its Cardmarket prices; the list shows the one chosen in Settings. */
    val prices: PriceSet,
    /** The card also exists as a jumbo print — the camera can't tell the size, so offer the switch. */
    val hasJumbo: Boolean = false,
    val condition: String = "NM",
) {
    /** How many copies this line added. */
    val quantity get() = result.copies

    fun unitPrice(type: PriceType): Double? = prices.best(type) ?: card.fallbackPrice
}

/** A recognised card waiting for "Add" (when auto-add is off). */
data class PendingScan(val card: TcgCard, val dataLang: String, val language: String?)

/**
 * Drives recognition: a card is added once it has been identified on two consecutive reads, and the
 * same card is not added again until it has left the frame. When the name fits several cards, scanning
 * pauses and the user picks the right one by its picture.
 */
class ScanController(
    private val c: AppContainer,
    private val target: CardTarget,
    private val scope: CoroutineScope,
) {
    private val recognizer = CardRecognizer(c.tcgdex, c.sets)
    var status by mutableStateOf("Hold a card inside the frame")
    var holo by mutableStateOf(false)
    var autoAdd by mutableStateOf(true)
    var pending by mutableStateOf<PendingScan?>(null)
    var choose by mutableStateOf<ScanResult.Choose?>(null)
    val added = mutableStateListOf<ScannedEntry>()
    var onAdded: () -> Unit = {}

    private var busy = false
    private var candidateKey: String? = null
    private var hits = 0
    private var lastAddedKey: String? = null
    private var lastSeen = 0L

    val analyzer = CardTextAnalyzer { clues -> onClues(clues) }

    /** Called on the main thread for every analysed frame. */
    private fun onClues(clues: ScanClues) {
        if (busy || choose != null) return
        val now = SystemClock.elapsedRealtime()
        if (!clues.hasAnything) {
            if (now - lastSeen > 1500) {
                lastAddedKey = null
                candidateKey = null
                hits = 0
                status = "Hold a card inside the frame"
            }
            return
        }
        lastSeen = now
        busy = true
        analyzer.paused.set(true)
        scope.launch {
            try {
                val result = recognizer.identify(clues)
                if (result == null) {
                    status = "Looking… ${clues.name ?: clues.number?.let { "#$it" } ?: ""}"
                    return@launch
                }
                lastSeen = SystemClock.elapsedRealtime()
                val key = when (result) {
                    is ScanResult.Found -> "${result.dataLang}/${result.card.id}"
                    is ScanResult.Choose -> "choose/${result.name}"
                }
                if (key == candidateKey) hits++ else {
                    candidateKey = key
                    hits = 1
                }
                if (hits < 2 || key == lastAddedKey) {
                    if (key == lastAddedKey && autoAdd) status = "Added! Show the next card 👍"
                    return@launch
                }
                lastAddedKey = key
                when (result) {
                    is ScanResult.Found -> if (autoAdd) add(result.card, result.dataLang, result.language) else {
                        pending = PendingScan(result.card, result.dataLang, result.language)
                        status = "Found ${result.card.name} — tap Add"
                    }
                    is ScanResult.Choose -> {
                        choose = result
                        status = "Which ${result.name} is it?"
                    }
                }
            } finally {
                busy = false
                analyzer.paused.set(false)
            }
        }
    }

    suspend fun add(card: TcgCard, dataLang: String, language: String?) {
        val printings = card.printings(dataLang)
        val ref = card.defaultPrinting(dataLang, holo)
        val lang = language ?: if (dataLang == "ja") "JA" else "EN"
        val hasJumbo = printings.any { it.oversized }
        val result = c.repo.add(target, ref, lang, hasJumbo = hasJumbo) ?: return
        added.add(0, ScannedEntry(ref, lang, result, c.repo.snapshot(ref), hasJumbo = hasJumbo))
        pending = null
        status = "Added ${card.name}!"
        onAdded()
    }

    /** One more copy of a card already scanned (its own line, so it can be undone on its own). */
    fun addAgain(e: ScannedEntry) = scope.launch {
        val result = c.repo.add(target, e.card, e.language, 1, e.condition, e.hasJumbo) ?: return@launch
        added.add(0, e.copy(result = result))
        onAdded()
    }

    /** Saves what was changed on a scanned card: the printing, condition, language and number of copies. */
    fun change(e: ScannedEntry, card: CardRef, condition: String, language: String, quantity: Int) = scope.launch {
        val result = c.repo.changeAdded(e.result, target, card, condition, language, quantity, e.hasJumbo) ?: return@launch
        val i = added.indexOf(e)
        val updated = e.copy(card = card, language = language, condition = condition, result = result, prices = c.repo.snapshot(card))
        if (i >= 0) added[i] = updated else added.add(0, updated)
    }

    fun pick(candidateId: String) {
        val ch = choose ?: return
        choose = null
        scope.launch {
            val card = runCatching { c.tcgdex.card(candidateId, ch.dataLang) }.getOrNull()
            if (card != null) add(card, ch.dataLang, ch.language) else status = "Couldn't load that card"
        }
    }

    fun dismissChoice() {
        choose = null
        status = "Hold a card inside the frame"
    }

    fun undo(e: ScannedEntry) = scope.launch {
        c.repo.undoAdd(e.result)
        added.remove(e)
        lastAddedKey = null
    }

}

@Composable
fun ScannerScreen(nav: NavController, target: CardTarget) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val priceType by c.settings.priceType.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA) }

    val controller = remember { ScanController(c, target, scope) }
    controller.onAdded = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    DisposableEffect(Unit) { onDispose { controller.analyzer.close() } }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var torch by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScannedEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Scan cards")
                        Text(rememberTargetLabel(target), style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { nav.popBackStack(); nav.openSearch(target) }) { Icon(Icons.Default.Search, "Search instead") }
                    IconButton(onClick = { torch = !torch }) {
                        Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, "Light")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = { nav.popBackStack() },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(if (controller.added.isEmpty()) "Done" else "Done · ${controller.added.size} card(s)") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (hasPermission) {
                Box(Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally).aspectRatio(3f / 4f)) {
                    CameraPreview(controller.analyzer, torch)
                }
            } else {
                EmptyState("📷", "Camera needed", "Allow the camera to scan cards, or use search instead.")
                TextButton(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Allow camera")
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(selected = controller.holo, onClick = { controller.holo = !controller.holo }, label = { Text("✨ Holo") })
                FilterChip(selected = controller.autoAdd, onClick = { controller.autoAdd = !controller.autoAdd }, label = { Text("Auto-add") })
                Text(
                    controller.status,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
            }
            controller.pending?.let { p ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CardImage(p.card.defaultPrinting(p.dataLang, controller.holo).thumbUrl, Modifier.width(44.dp), fallbackKey = ImageKey(p.card.id, p.dataLang))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.card.name, style = MaterialTheme.typography.titleSmall)
                            Text("${p.card.set.name} · #${p.card.localId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { scope.launch { controller.add(p.card, p.dataLang, p.language) } }) { Text("Add") }
                    }
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(controller.added, key = { System.identityHashCode(it) }) { e ->
                    ScannedRow(e, e.unitPrice(priceType), onClick = { editing = e }, onAddAgain = { controller.addAgain(e) }, onUndo = { controller.undo(e) })
                }
            }
        }
    }
    controller.choose?.let { ch ->
        AlertDialog(
            onDismissRequest = { controller.dismissChoice() },
            title = { Text("Which ${ch.name} is it?") },
            text = {
                LazyVerticalGrid(columns = GridCells.Adaptive(96.dp), modifier = Modifier.heightIn(max = 480.dp)) {
                    items(ch.candidates, key = { it.brief.id }) { cand ->
                        Column(Modifier.clickable { controller.pick(cand.brief.id) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CardImage(cand.brief.thumbUrl(ch.dataLang), Modifier.fillMaxWidth(), fallbackKey = ImageKey(cand.brief.id, ch.dataLang), placeholder = cand.brief.name + "\n#" + cand.brief.localId)
                            Text(cand.setName, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                            Text("#${cand.brief.localId}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { controller.dismissChoice() }) { Text("None of these") } },
        )
    }

    editing?.let { e ->
        CardDialog(
            card = e.card,
            initial = EditValues(e.quantity, e.condition, e.language, null),
            priceType = priceType,
            confirmLabel = "Save",
            allowCustomPrice = false,
            onDismiss = { editing = null },
            onConfirm = { card, v ->
                editing = null
                controller.change(e, card, v.condition, v.language, v.quantity)
            },
        )
    }
}

/** One scanned card: picture, name, set and variant, price, and buttons for another copy or undo. Tap to change it. */
@Composable
private fun ScannedRow(e: ScannedEntry, price: Double?, onClick: () -> Unit, onAddAgain: () -> Unit, onUndo: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            CardImage(e.card.thumbUrl, Modifier.width(40.dp), fallbackKey = ImageKey(e.card.cardId, e.card.dataLang, e.card.variantId))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (e.quantity > 1) "${e.quantity}× " else "") + e.card.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${e.card.setName} · #${e.card.numberLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    variantBadge(e.card)?.let { Tag(it, HoloColor, Color.White) }
                    if (e.language != "EN") Tag(e.language, Color(0xFFBC002D), Color.White)
                    if (e.condition != "NM") Tag(e.condition)
                    if (e.hasJumbo && !e.card.oversized) {
                        Text("Big card? Tap it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.money(price), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                if (e.quantity > 1) {
                    Text(Fmt.money(price?.let { it * e.quantity }) + " total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onAddAgain) { Icon(Icons.Default.Add, "Add another copy") }
            IconButton(onClick = onUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
        }
    }
}

@Composable
private fun CameraPreview(analyzer: ImageAnalysis.Analyzer, torch: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val selector4x3 = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val analysisSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector4x3).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, analyzer)
            try {
                p.unbindAll()
                camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                camera = null
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    detectTapGestures { pos ->
                        val point = previewView.meteringPointFactory.createPoint(pos.x, pos.y)
                        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    }
                },
        ) {
            val g = ScanGuide.boxFor(size.width, size.height)
            val dim = Color.Black.copy(alpha = 0.45f)
            drawRect(dim, Offset.Zero, GSize(size.width, g.top))
            drawRect(dim, Offset(0f, g.bottom), GSize(size.width, size.height - g.bottom))
            drawRect(dim, Offset(0f, g.top), GSize(g.left, g.height))
            drawRect(dim, Offset(g.right, g.top), GSize(size.width - g.right, g.height))
            drawRoundRect(
                Color(0xFFFFCB05),
                Offset(g.left, g.top),
                GSize(g.width, g.height),
                CornerRadius(16f, 16f),
                style = Stroke(width = 4.dp.toPx()),
            )
        }
    }
}
