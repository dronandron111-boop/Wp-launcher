package com.lumia.wplauncher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.roundToInt

enum class TileSize(val cols: Int, val rows: Int) { SMALL(1, 1), MEDIUM(2, 2), WIDE(4, 2) }
enum class TileSpecialType { NONE, PHONE, PEOPLE_LIVE, CALENDAR_LIVE, STORE_BADGE, LAUNCHER_SETTINGS, GALLERY_LIVE }

data class TileItem(
    val id: String,
    val title: String,
    val size: TileSize,
    val packageName: String? = null,
    val specialType: TileSpecialType = TileSpecialType.NONE,
    val iconName: String = "apps",
    val badgeCount: Int = 0
)

data class AppEntry(val label: String, val packageName: String, val icon: android.graphics.drawable.Drawable?)

val WP_ACCENTS = listOf(
    0xFFE51400, 0xFF1BA1E2, 0xFF008A00, 0xFFF09609,
    0xFFD80073, 0xFF0050EF, 0xFFAA00FF, 0xFFA4C400,
    0xFF00ABA9, 0xFF60A917, 0xFFFA6800, 0xFF647687
)

object Storage {
    fun getAccent(ctx: Context): Long =
        ctx.getSharedPreferences("wp8", Context.MODE_PRIVATE).getLong("accent", 0xFF1BA1E2)

    fun saveAccent(ctx: Context, hex: Long) =
        ctx.getSharedPreferences("wp8", Context.MODE_PRIVATE).edit().putLong("accent", hex).apply()

    fun isDarkTheme(ctx: Context): Boolean =
        ctx.getSharedPreferences("wp8", Context.MODE_PRIVATE).getBoolean("dark_theme", true)

    fun saveDarkTheme(ctx: Context, dark: Boolean) =
        ctx.getSharedPreferences("wp8", Context.MODE_PRIVATE).edit().putBoolean("dark_theme", dark).apply()

    fun getGridColumns(ctx: Context): Int =
        ctx.getSharedPreferences("wp8", Context.MODE_PRIVATE).getInt("grid_cols", 4)

    fun saveGridColumns(ctx: Context, cols: Int) =
        ctx.getSharedPreferences("wp8", Context.MODE_PRIVATE).edit().putInt("grid_cols", cols).apply()

    fun loadTiles(ctx: Context): List<TileItem> {
        val raw = ctx.getSharedPreferences("wp8", Context.MODE_PRIVATE).getString("tiles", null)
            ?: return defaultTiles()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                TileItem(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    size = TileSize.valueOf(o.optString("size", "MEDIUM")),
                    packageName = o.optString("pkg", "").ifEmpty { null },
                    specialType = TileSpecialType.valueOf(o.optString("spec", "NONE")),
                    iconName = o.optString("icon", "apps"),
                    badgeCount = o.optInt("badge", 0)
                )
            }.ifEmpty { defaultTiles() }
        } catch (e: Exception) { defaultTiles() }
    }

    fun saveTiles(ctx: Context, list: List<TileItem>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("title", t.title); put("size", t.size.name)
                put("pkg", t.packageName ?: ""); put("spec", t.specialType.name)
                put("icon", t.iconName); put("badge", t.badgeCount)
            })
        }
        ctx.getSharedPreferences("wp8", Context.MODE_PRIVATE).edit().putString("tiles", arr.toString()).apply()
    }

    fun defaultTiles() = listOf(
        TileItem("phone", "Телефон", TileSize.MEDIUM, specialType = TileSpecialType.PHONE, iconName = "call"),
        TileItem("messages", "Сообщения", TileSize.SMALL, iconName = "message"),
        TileItem("browser", "Браузер", TileSize.SMALL, iconName = "language"),
        TileItem("mail", "Почта", TileSize.SMALL, iconName = "email"),
        TileItem("store", "Маркет", TileSize.SMALL, specialType = TileSpecialType.STORE_BADGE, iconName = "shopping_bag", badgeCount = 16),
        TileItem("contacts", "Контакты", TileSize.MEDIUM, specialType = TileSpecialType.PEOPLE_LIVE, iconName = "people"),
        TileItem("calendar", "Календарь", TileSize.MEDIUM, specialType = TileSpecialType.CALENDAR_LIVE, iconName = "event"),
        TileItem("gallery", "Галерея", TileSize.MEDIUM, specialType = TileSpecialType.GALLERY_LIVE, iconName = "photo_library"),
        TileItem("camera", "Камера", TileSize.MEDIUM, iconName = "photo_camera"),
        TileItem("settings", "Настройки", TileSize.MEDIUM, specialType = TileSpecialType.LAUNCHER_SETTINGS, iconName = "settings")
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemStatusBar()
        setContent { WpApp() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemStatusBar()
    }

    private fun hideSystemStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WpApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { 2 })

    var accentHex by remember { mutableLongStateOf(Storage.getAccent(ctx)) }
    var isDark by remember { mutableStateOf(Storage.isDarkTheme(ctx)) }
    var gridCols by remember { mutableIntStateOf(Storage.getGridColumns(ctx)) }

    val accent = Color(accentHex)
    val bgColor = if (isDark) Color.Black else Color.White
    val fgColor = if (isDark) Color.White else Color.Black

    var tiles by remember { mutableStateOf(Storage.loadTiles(ctx)) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var editingTileId by remember { mutableStateOf<String?>(null) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var galleryPhotos by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }

    val mediaPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasMediaPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, mediaPerm) == PackageManager.PERMISSION_GRANTED)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMediaPerm = granted
    }

    LaunchedEffect(Unit) {
        if (!hasMediaPerm) {
            permLauncher.launch(mediaPerm)
        }
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        apps = pm.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, try { it.loadIcon(pm) } catch (e: Exception) { null }) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    LaunchedEffect(hasMediaPerm) {
        if (hasMediaPerm) {
            galleryPhotos = loadRecentPhotos(ctx, 10)
        }
    }

    BackHandler(enabled = showSettingsScreen || editingTileId != null || pager.currentPage != 0) {
        when {
            showSettingsScreen -> showSettingsScreen = false
            editingTileId != null -> editingTileId = null
            pager.currentPage != 0 -> scope.launch { pager.animateScrollToPage(0) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .navigationBarsPadding()
    ) {
        // Custom Windows Phone 8 Status Bar flush with top cutout
        WpStatusBar(fgColor = fgColor, accentColor = accent)

        if (showSettingsScreen) {
            WpSettingsScreen(
                bgColor = bgColor,
                fgColor = fgColor,
                accentColor = accent,
                currentAccent = accentHex,
                isDark = isDark,
                gridCols = gridCols,
                onToggleTheme = { dark ->
                    isDark = dark
                    Storage.saveDarkTheme(ctx, dark)
                },
                onSelectAccent = { hex ->
                    accentHex = hex
                    Storage.saveAccent(ctx, hex)
                },
                onSelectCols = { cols ->
                    gridCols = cols
                    Storage.saveGridColumns(ctx, cols)
                },
                onSetDefaultHome = {
                    ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                },
                onResetTiles = {
                    val d = Storage.defaultTiles()
                    tiles = d
                    Storage.saveTiles(ctx, d)
                    showSettingsScreen = false
                },
                onClose = { showSettingsScreen = false }
            )
        } else {
            HorizontalPager(
                state = pager,
                userScrollEnabled = editingTileId == null,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                if (page == 0) {
                    StartScreen(
                        tiles = tiles,
                        gridCols = gridCols,
                        accent = accent,
                        fgColor = fgColor,
                        galleryPhotos = galleryPhotos,
                        editingTileId = editingTileId,
                        onSelectEditTile = { editingTileId = it },
                        onExitEditMode = { editingTileId = null },
                        onReorderTiles = { newList ->
                            tiles = newList
                            Storage.saveTiles(ctx, newList)
                        },
                        onCycleSize = { id ->
                            val updated = tiles.map { t ->
                                if (t.id == id) {
                                    val next = when (t.size) {
                                        TileSize.SMALL -> TileSize.MEDIUM
                                        TileSize.MEDIUM -> TileSize.WIDE
                                        TileSize.WIDE -> TileSize.SMALL
                                    }
                                    t.copy(size = next)
                                } else t
                            }
                            tiles = updated
                            Storage.saveTiles(ctx, updated)
                        },
                        onUnpin = { id ->
                            val updated = tiles.filterNot { it.id == id }
                            tiles = updated
                            Storage.saveTiles(ctx, updated)
                            if (editingTileId == id) editingTileId = null
                        },
                        onClick = { t ->
                            if (t.specialType == TileSpecialType.LAUNCHER_SETTINGS) {
                                showSettingsScreen = true
                            } else {
                                if (isGalleryTile(t) && !hasMediaPerm) {
                                    permLauncher.launch(mediaPerm)
                                }
                                launchTile(ctx, t)
                            }
                        },
                        onAllApps = { scope.launch { pager.animateScrollToPage(1) } }
                    )
                } else {
                    AllAppsScreen(
                        apps = apps,
                        accent = accent,
                        bgColor = bgColor,
                        fgColor = fgColor,
                        onClick = { app -> ctx.packageManager.getLaunchIntentForPackage(app.packageName)?.let { ctx.startActivity(it) } },
                        onPin = { app ->
                            val isGal = app.packageName.contains("gallery", true) ||
                                app.packageName.contains("photos", true) ||
                                app.label.contains("галерея", true) ||
                                app.label.contains("фото", true)
                            val updated = tiles + TileItem(
                                id = "app_${System.currentTimeMillis()}",
                                title = app.label,
                                size = TileSize.MEDIUM,
                                packageName = app.packageName,
                                specialType = if (isGal) TileSpecialType.GALLERY_LIVE else TileSpecialType.NONE
                            )
                            tiles = updated
                            Storage.saveTiles(ctx, updated)
                            scope.launch { pager.animateScrollToPage(0) }
                        },
                        onSettings = { showSettingsScreen = true }
                    )
                }
            }
        }
    }
}

fun isGalleryTile(t: TileItem): Boolean {
    if (t.specialType == TileSpecialType.GALLERY_LIVE) return true
    val pkg = t.packageName ?: ""
    return pkg.contains("gallery", true) ||
        pkg.contains("photos", true) ||
        t.title.contains("галерея", true) ||
        t.title.contains("фото", true)
}

suspend fun loadRecentPhotos(ctx: Context, limit: Int): List<ImageBitmap> = withContext(Dispatchers.IO) {
    val result = mutableListOf<ImageBitmap>()
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
    try {
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && result.size < limit) {
                val id = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val bmp: Bitmap? = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ctx.contentResolver.loadThumbnail(contentUri, android.util.Size(360, 360), null)
                    } else {
                        ctx.contentResolver.openInputStream(contentUri)?.use { stream ->
                            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                            BitmapFactory.decodeStream(stream, null, opts)
                        }
                    }
                } catch (_: Exception) { null }
                if (bmp != null) {
                    result.add(bmp.asImageBitmap())
                }
            }
        }
    } catch (_: Exception) {}
    result
}

@Composable
fun WpStatusBar(fgColor: Color, accentColor: Color) {
    val ctx = LocalContext.current
    var timeText by remember { mutableStateOf("") }
    var batteryPct by remember { mutableIntStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }
    var hasWifi by remember { mutableStateOf(false) }
    var operatorName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        while (true) {
            timeText = LocalTime.now().format(fmt)
            try {
                val batIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = batIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val status = batIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                if (level >= 0 && scale > 0) batteryPct = (level * 100) / scale
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            } catch (_: Exception) {}

            try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
                hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } catch (_: Exception) {}

            try {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                operatorName = tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: ""
            } catch (_: Exception) {}

            delay(5000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
                listOf(4.dp, 6.dp, 8.dp, 10.dp, 12.dp).forEachIndexed { idx, h ->
                    val active = operatorName.isNotEmpty() || idx < 3
                    Box(
                        Modifier
                            .width(2.5.dp)
                            .height(h)
                            .background(if (active) fgColor else fgColor.copy(alpha = 0.25f))
                    )
                }
            }
            if (operatorName.isNotEmpty()) {
                Text(
                    text = operatorName,
                    color = fgColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
            if (hasWifi) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = "Wi-Fi",
                    tint = fgColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(10.dp)
                        .border(1.dp, fgColor)
                        .padding(1.5.dp)
                ) {
                    val fillColor = when {
                        isCharging -> accentColor
                        batteryPct <= 20 -> Color(0xFFE51400)
                        else -> fgColor
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((batteryPct / 100f).coerceIn(0.05f, 1f))
                            .background(fillColor)
                    )
                }
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .height(5.dp)
                        .background(fgColor)
                )
            }
            Text(
                text = "$batteryPct%",
                color = fgColor.copy(alpha = 0.8f),
                fontSize = 11.sp
            )
            Text(
                text = timeText,
                color = fgColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

data class PlacedTile(val tile: TileItem, val col: Int, val row: Int)

fun placeTiles(tiles: List<TileItem>, totalCols: Int): Pair<List<PlacedTile>, Int> {
    val occ = mutableSetOf<Pair<Int, Int>>()
    val res = mutableListOf<PlacedTile>()
    var maxR = 0
    for (t in tiles) {
        val w = t.size.cols.coerceAtMost(totalCols)
        val h = t.size.rows
        var r = 0
        var done = false
        while (!done) {
            for (c in 0..(totalCols - w)) {
                val fits = (0 until h).all { dr -> (0 until w).all { dc -> (r + dr to c + dc) !in occ } }
                if (fits) {
                    for (dr in 0 until h) for (dc in 0 until w) occ.add(r + dr to c + dc)
                    res.add(PlacedTile(t, c, r))
                    if (r + h > maxR) maxR = r + h
                    done = true
                    break
                }
            }
            r++
        }
    }
    return res to maxR
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StartScreen(
    tiles: List<TileItem>,
    gridCols: Int,
    accent: Color,
    fgColor: Color,
    galleryPhotos: List<ImageBitmap>,
    editingTileId: String?,
    onSelectEditTile: (String) -> Unit,
    onExitEditMode: () -> Unit,
    onReorderTiles: (List<TileItem>) -> Unit,
    onCycleSize: (String) -> Unit,
    onUnpin: (String) -> Unit,
    onClick: (TileItem) -> Unit,
    onAllApps: () -> Unit
) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val gridW = LocalConfiguration.current.screenWidthDp.dp - 44.dp
    val gap = if (gridCols == 6) 5.dp else 7.dp
    val cell = (gridW - gap * (gridCols - 1)) / gridCols

    val (placed, rows) = remember(tiles, gridCols) { placeTiles(tiles, gridCols) }
    val gridH = if (rows > 0) cell * rows + gap * (rows - 1) else 200.dp

    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    val cellStepPx = with(density) { (cell + gap).toPx() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                enabled = editingTileId != null,
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) { onExitEditMode() }
            .verticalScroll(rememberScrollState(), enabled = draggingId == null)
            .padding(start = 12.dp, top = 6.dp, bottom = 32.dp)
    ) {
        Box(
            modifier = Modifier
                .width(gridW)
                .height(gridH)
        ) {
            placed.forEach { p ->
                val isEditingThis = editingTileId == p.tile.id
                val isDraggingThis = draggingId == p.tile.id
                val dimOthers = editingTileId != null && !isEditingThis

                val wCols = p.tile.size.cols.coerceAtMost(gridCols)
                val w = cell * wCols + gap * (wCols - 1)
                val h = cell * p.tile.size.rows + gap * (p.tile.size.rows - 1)

                val baseX = with(density) { ((cell + gap) * p.col).toPx() }
                val baseY = with(density) { ((cell + gap) * p.row).toPx() }

                val currentOffsetX = if (isDraggingThis) baseX + dragOffset.x else baseX
                val currentOffsetY = if (isDraggingThis) baseY + dragOffset.y else baseY

                val scale by animateFloatAsState(
                    targetValue = when {
                        isDraggingThis -> 1.07f
                        isEditingThis -> 1.03f
                        else -> 1f
                    },
                    label = "tileScale"
                )

                Box(
                    modifier = Modifier
                        .zIndex(if (isDraggingThis) 10f else if (isEditingThis) 5f else 1f)
                        .offset { IntOffset(currentOffsetX.roundToInt(), currentOffsetY.roundToInt()) }
                        .scale(scale)
                        .width(w)
                        .height(h)
                        .alpha(if (dimOthers) 0.45f else 1f)
                        .background(accent)
                        .then(
                            if (isEditingThis) Modifier.border(2.dp, fgColor) else Modifier
                        )
                        .pointerInput(p.tile.id, editingTileId, placed) {
                            if (isEditingThis) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingId = p.tile.id
                                        dragOffset = Offset.Zero
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffset += dragAmount

                                        val centerCol = ((baseX + dragOffset.x) / cellStepPx).roundToInt().coerceIn(0, gridCols - 1)
                                        val centerRow = ((baseY + dragOffset.y) / cellStepPx).roundToInt().coerceAtLeast(0)

                                        val targetPlaced = placed.firstOrNull { cand ->
                                            cand.tile.id != p.tile.id &&
                                                centerRow in cand.row until (cand.row + cand.tile.size.rows) &&
                                                centerCol in cand.col until (cand.col + cand.tile.size.cols.coerceAtMost(gridCols))
                                        }

                                        if (targetPlaced != null) {
                                            val fromIdx = tiles.indexOfFirst { it.id == p.tile.id }
                                            val toIdx = tiles.indexOfFirst { it.id == targetPlaced.tile.id }
                                            if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                                                val mutable = tiles.toMutableList()
                                                val moved = mutable.removeAt(fromIdx)
                                                mutable.add(toIdx, moved)
                                                onReorderTiles(mutable)
                                                dragOffset = Offset.Zero
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        draggingId = null
                                        dragOffset = Offset.Zero
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        dragOffset = Offset.Zero
                                    }
                                )
                            }
                        }
                        .combinedClickable(
                            onClick = {
                                if (editingTileId != null) {
                                    if (editingTileId != p.tile.id) onSelectEditTile(p.tile.id)
                                    else onExitEditMode()
                                } else {
                                    onClick(p.tile)
                                }
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSelectEditTile(p.tile.id)
                            }
                        )
                ) {
                    when {
                        p.tile.specialType == TileSpecialType.PEOPLE_LIVE -> PeopleTile(p.tile.title, accent)
                        p.tile.specialType == TileSpecialType.CALENDAR_LIVE -> CalendarTile(p.tile.title)
                        isGalleryTile(p.tile) && galleryPhotos.isNotEmpty() -> {
                            GalleryLiveTile(
                                title = p.tile.title,
                                photos = galleryPhotos,
                                showTitle = p.tile.size != TileSize.SMALL
                            )
                        }
                        p.tile.specialType == TileSpecialType.PHONE -> {
                            val op = remember {
                                try {
                                    (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
                                        ?.networkOperatorName?.ifBlank { "Нет SIM-карты" } ?: "Нет SIM-карты"
                                } catch (_: Exception) { "Нет SIM-карты" }
                            }
                            Box(Modifier.fillMaxSize().padding(10.dp)) {
                                Icon(Icons.Default.Call, null, tint = Color.White, modifier = Modifier.size(48.dp).align(Alignment.Center))
                                if (p.tile.size != TileSize.SMALL) {
                                    Text(op, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart))
                                }
                            }
                        }
                        p.tile.specialType == TileSpecialType.STORE_BADGE -> {
                            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ShoppingBag, null, tint = Color.White, modifier = Modifier.size(26.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(p.tile.badgeCount.toString(), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Light)
                            }
                        }
                        else -> {
                            val bmp = remember(p.tile.packageName) {
                                p.tile.packageName?.let { pkg ->
                                    try {
                                        val d = ctx.packageManager.getApplicationIcon(pkg)
                                        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                                        val c = Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(c); b.asImageBitmap()
                                    } catch (_: Exception) { null }
                                }
                            }
                            Box(Modifier.fillMaxSize().padding(if (p.tile.size == TileSize.SMALL) 6.dp else 10.dp)) {
                                if (bmp != null) Image(bmp, null, Modifier.size(if (p.tile.size == TileSize.SMALL) 32.dp else 46.dp).align(Alignment.Center))
                                else Icon(iconFor(p.tile.iconName), null, tint = Color.White, modifier = Modifier.size(if (p.tile.size == TileSize.SMALL) 30.dp else 48.dp).align(Alignment.Center))
                                if (p.tile.size != TileSize.SMALL) {
                                    Text(p.tile.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart))
                                }
                            }
                        }
                    }

                    if (isEditingThis) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(26.dp)
                                .background(Color.Black, CircleShape)
                                .border(1.5.dp, Color.White, CircleShape)
                                .clickable { onUnpin(p.tile.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Открепить", tint = Color.White, modifier = Modifier.size(15.dp))
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 4.dp, y = 4.dp)
                                .size(26.dp)
                                .background(Color.Black, CircleShape)
                                .border(1.5.dp, Color.White, CircleShape)
                                .clickable { onCycleSize(p.tile.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.OpenInFull, contentDescription = "Размер", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        if (editingTileId != null) {
            Row(
                modifier = Modifier.width(gridW),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Тяните плитку • ↘ размер • × убрать",
                    color = fgColor.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
                TextButton(onClick = onExitEditMode) {
                    Text("Готово", color = accent, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .width(gridW)
                    .clickable { onAllApps() }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("все приложения", color = fgColor.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(end = 10.dp))
                Box(Modifier.size(34.dp).border(1.5.dp, fgColor, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = fgColor, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun GalleryLiveTile(title: String, photos: List<ImageBitmap>, showTitle: Boolean) {
    var index by remember(photos.size) { mutableIntStateOf(0) }
    LaunchedEffect(photos.size) {
        if (photos.size > 1) {
            while (true) {
                delay(4200)
                index = (index + 1) % photos.size
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(
            targetState = photos[index % photos.size],
            animationSpec = tween(durationMillis = 900),
            label = "galleryCrossfade"
        ) { currentPhoto ->
            Image(
                bitmap = currentPhoto,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (showTitle) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                        )
                    )
            )
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            )
        }
    }
}

@Composable
fun WpSettingsScreen(
    bgColor: Color,
    fgColor: Color,
    accentColor: Color,
    currentAccent: Long,
    isDark: Boolean,
    gridCols: Int,
    onToggleTheme: (Boolean) -> Unit,
    onSelectAccent: (Long) -> Unit,
    onSelectCols: (Int) -> Unit,
    onSetDefaultHome: () -> Unit,
    onResetTiles: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = fgColor)
            }
            Spacer(Modifier.width(8.dp))
            Text("НАСТРОЙКИ", color = fgColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        }

        Text(
            text = "экран и тема",
            color = fgColor,
            fontSize = 38.sp,
            fontWeight = FontWeight.ExtraLight,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        Text("Фон", color = fgColor.copy(alpha = 0.65f), fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 22.dp)) {
            listOf(true to "Тёмный", false to "Светлый").forEach { (darkVal, label) ->
                val selected = isDark == darkVal
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(if (selected) accentColor else Color.Transparent)
                        .border(1.5.dp, if (selected) accentColor else fgColor.copy(alpha = 0.4f))
                        .clickable { onToggleTheme(darkVal) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (selected) Color.White else fgColor, fontSize = 16.sp)
                }
            }
        }

        Text("Цвет элементов", color = fgColor.copy(alpha = 0.65f), fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        WP_ACCENTS.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                row.forEach { hex ->
                    val selected = hex == currentAccent
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .background(Color(hex))
                            .then(if (selected) Modifier.border(3.dp, fgColor) else Modifier)
                            .clickable { onSelectAccent(hex) }
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Text("Сетка рабочего стола", color = fgColor.copy(alpha = 0.65f), fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 24.dp)) {
            listOf(4 to "4 колонки (Классика)", 6 to "6 колонок (Lumia 1520)").forEach { (colsVal, label) ->
                val selected = gridCols == colsVal
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .background(if (selected) accentColor else Color.Transparent)
                        .border(1.5.dp, if (selected) accentColor else fgColor.copy(alpha = 0.4f))
                        .clickable { onSelectCols(colsVal) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (selected) Color.White else fgColor, fontSize = 13.sp, maxLines = 1)
                }
            }
        }

        HorizontalDivider(color = fgColor.copy(alpha = 0.2f))
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onSetDefaultHome,
            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
            shape = androidx.compose.ui.graphics.RectangleShape,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Сделать лаунчером по умолчанию", color = Color.White, fontSize = 16.sp)
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onResetTiles,
            shape = androidx.compose.ui.graphics.RectangleShape,
            border = BorderStroke(1.5.dp, fgColor.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Сбросить раскладку плиток", color = fgColor, fontSize = 16.sp)
        }
    }
}

@Composable
fun PeopleTile(title: String, accent: Color) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    val shades = listOf(accent, Color.White.copy(0.22f), Color.White.copy(0.38f), Color.Black.copy(0.16f), Color.White.copy(0.55f))
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            for (r in 0 until 3) Row(Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0 until 3) {
                    val col by animateColorAsState(shades[(r * 3 + c + tick) % shades.size], tween(700), label = "")
                    Box(Modifier.weight(1f).fillMaxHeight().background(col))
                }
            }
        }
        Text(title, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
    }
}

@Composable
fun CalendarTile(title: String) {
    val d = remember { LocalDate.now() }
    val dow = d.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale("ru")).replaceFirstChar { it.uppercase() }.trimEnd('.')
    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(title, color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomStart))
        Row(Modifier.align(Alignment.BottomEnd), verticalAlignment = Alignment.Bottom) {
            Text(dow, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(bottom = 5.dp, end = 4.dp))
            Text(d.dayOfMonth.toString(), color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Light, lineHeight = 42.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AllAppsScreen(
    apps: List<AppEntry>,
    accent: Color,
    bgColor: Color,
    fgColor: Color,
    onClick: (AppEntry) -> Unit,
    onPin: (AppEntry) -> Unit,
    onSettings: () -> Unit
) {
    var q by remember { mutableStateOf("") }
    val list = remember(apps, q) { if (q.isBlank()) apps else apps.filter { it.label.contains(q, true) } }
    Column(Modifier.fillMaxSize().background(bgColor).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .background(if (bgColor == Color.Black) Color.White else Color(0xFFEDEDED))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (q.isEmpty()) Text("поиск...", color = Color.Gray, fontSize = 16.sp)
                BasicTextField(
                    value = q,
                    onValueChange = { q = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
                    cursorBrush = SolidColor(accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(42.dp).background(accent).clickable { onSettings() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Settings, null, tint = Color.White)
            }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.packageName }) { app ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onClick(app) }, onLongClick = { onPin(app) })
                        .padding(vertical = 4.dp)
                ) {
                    Box(Modifier.size(44.dp).background(accent), contentAlignment = Alignment.Center) {
                        val bmp = remember(app.packageName) {
                            app.icon?.let { d ->
                                val b = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
                                val c = Canvas(b); d.setBounds(0, 0, 80, 80); d.draw(c); b.asImageBitmap()
                            }
                        }
                        if (bmp != null) Image(bmp, null, Modifier.size(30.dp))
                        else Icon(Icons.Default.Apps, null, tint = Color.White)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(app.label, color = fgColor, fontSize = 19.sp, fontWeight = FontWeight.Light, maxLines = 1)
                }
            }
        }
    }
}

fun launchTile(ctx: Context, tile: TileItem) {
    if (!tile.packageName.isNullOrBlank()) {
        ctx.packageManager.getLaunchIntentForPackage(tile.packageName)?.let { ctx.startActivity(it); return }
    }
    val i = when (tile.id) {
        "phone" -> Intent(Intent.ACTION_DIAL)
        "messages" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_MESSAGING) }
        "browser" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
        "mail" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_EMAIL) }
        "store" -> Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=apps"))
        "contacts" -> Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
        "gallery" -> Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        "office" -> Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        "calendar" -> Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build())
        "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        else -> Intent(Settings.ACTION_SETTINGS)
    }
    try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i) }
    catch (_: Exception) { Toast.makeText(ctx, "Не найдено: ${tile.title}", Toast.LENGTH_SHORT).show() }
}

fun iconFor(name: String): ImageVector = when (name) {
    "call" -> Icons.Default.Call
    "message" -> Icons.AutoMirrored.Filled.Message
    "language" -> Icons.Default.Language
    "email" -> Icons.Default.Email
    "shopping_bag" -> Icons.Default.ShoppingBag
    "people" -> Icons.Default.People
    "photo_library" -> Icons.Default.PhotoLibrary
    "description" -> Icons.Default.Description
    "event" -> Icons.Default.Event
    "photo_camera" -> Icons.Default.PhotoCamera
    "settings" -> Icons.Default.Settings
    else -> Icons.Default.Apps
}
