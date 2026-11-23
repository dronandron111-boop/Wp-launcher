package com.lumia.wplauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class TileSize(val cols: Int, val rows: Int) { SMALL(1, 1), MEDIUM(2, 2), WIDE(4, 2) }

enum class TileSpecialType {
    NONE, PHONE, PEOPLE_LIVE, CALENDAR_LIVE, STORE_BADGE, LAUNCHER_SETTINGS, GALLERY_LIVE,
    WIDGET_DIGITAL_CLOCK, WIDGET_ANALOG_CLOCK, WIDGET_WEATHER, WIDGET_MUSIC,
    WIDGET_BATTERY, WIDGET_MEMORY, WIDGET_QUICK_TOGGLES
}

enum class WallpaperMode { WP8_INSIDE_TILES, W10_FULLSCREEN }

enum class IconMode(val id: String, val title: String) {
    SYSTEM("system", "Системные иконки"),
    WHITE_WP("white_wp", "Монохромные белые (WP)")
}

data class TileItem(
    val id: String,
    val title: String,
    val size: TileSize,
    val packageName: String? = null,
    val specialType: TileSpecialType = TileSpecialType.NONE,
    val iconName: String = "apps",
    val badgeCount: Int = 0,
    val customColorHex: Long? = null,
    val customAlpha: Float? = null
)

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean
)

data class IconPackInfo(val packageName: String, val label: String, val icon: Drawable?)
data class ContactResult(val id: Long, val name: String, val phone: String)
data class FileResult(val name: String, val path: String, val isDir: Boolean)
data class SettingShortcut(val title: String, val subtitle: String, val action: String)

val WP_ACCENTS = listOf(
    0xFFE51400, 0xFF1BA1E2, 0xFF008A00, 0xFFF09609,
    0xFFD80073, 0xFF0050EF, 0xFFAA00FF, 0xFFA4C400,
    0xFF00ABA9, 0xFF60A917, 0xFFFA6800, 0xFF647687
)

val SYSTEM_SHORTCUTS = listOf(
    SettingShortcut("Wi-Fi и сети", "Настройки беспроводного подключения", Settings.ACTION_WIFI_SETTINGS),
    SettingShortcut("Bluetooth", "Подключённые устройства", Settings.ACTION_BLUETOOTH_SETTINGS),
    SettingShortcut("Экран и яркость", "Настройки дисплея", Settings.ACTION_DISPLAY_SETTINGS),
    SettingShortcut("Звук и вибрация", "Громкость и мелодии", Settings.ACTION_SOUND_SETTINGS),
    SettingShortcut("Приложения", "Управление установленными программами", Settings.ACTION_APPLICATION_SETTINGS),
    SettingShortcut("Батарея", "Энергосбережение и расход заряда", Intent.ACTION_POWER_USAGE_SUMMARY),
    SettingShortcut("Память и хранилище", "Свободное место на устройстве", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
    SettingShortcut("Лаунчер по умолчанию", "Выбор главного экрана", Settings.ACTION_HOME_SETTINGS)
)

object Storage {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("wp8", Context.MODE_PRIVATE)

    fun getAccent(ctx: Context): Long = prefs(ctx).getLong("accent", 0xFF1BA1E2)
    fun saveAccent(ctx: Context, hex: Long) = prefs(ctx).edit().putLong("accent", hex).apply()

    fun isDarkTheme(ctx: Context): Boolean = prefs(ctx).getBoolean("dark_theme", true)
    fun saveDarkTheme(ctx: Context, dark: Boolean) = prefs(ctx).edit().putBoolean("dark_theme", dark).apply()

    fun getGridColumns(ctx: Context): Int = prefs(ctx).getInt("grid_cols", 4)
    fun saveGridColumns(ctx: Context, cols: Int) = prefs(ctx).edit().putInt("grid_cols", cols).apply()

    fun getWallpaperUri(ctx: Context): String? = prefs(ctx).getString("wallpaper_uri", null)
    fun saveWallpaperUri(ctx: Context, uri: String?) = prefs(ctx).edit().putString("wallpaper_uri", uri).apply()

    fun getWallpaperMode(ctx: Context): WallpaperMode = try {
        WallpaperMode.valueOf(prefs(ctx).getString("wallpaper_mode", WallpaperMode.WP8_INSIDE_TILES.name)!!)
    } catch (_: Exception) { WallpaperMode.WP8_INSIDE_TILES }
    fun saveWallpaperMode(ctx: Context, mode: WallpaperMode) = prefs(ctx).edit().putString("wallpaper_mode", mode.name).apply()

    fun getGlobalTileAlpha(ctx: Context): Float = prefs(ctx).getFloat("global_tile_alpha", 0.85f)
    fun saveGlobalTileAlpha(ctx: Context, a: Float) = prefs(ctx).edit().putFloat("global_tile_alpha", a).apply()

    fun getAppListBlur(ctx: Context): Float = prefs(ctx).getFloat("applist_blur", 18f)
    fun saveAppListBlur(ctx: Context, b: Float) = prefs(ctx).edit().putFloat("applist_blur", b).apply()

    fun isAppListGrid(ctx: Context): Boolean = prefs(ctx).getBoolean("applist_grid", false)
    fun saveAppListGrid(ctx: Context, isGrid: Boolean) = prefs(ctx).edit().putBoolean("applist_grid", isGrid).apply()

    fun getAppListCols(ctx: Context): Int = prefs(ctx).getInt("applist_cols", 4)
    fun saveAppListCols(ctx: Context, cols: Int) = prefs(ctx).edit().putInt("applist_cols", cols).apply()

    fun getAppIconPadding(ctx: Context): Float = prefs(ctx).getFloat("app_icon_padding", 6f)
    fun saveAppIconPadding(ctx: Context, pad: Float) = prefs(ctx).edit().putFloat("app_icon_padding", pad).apply()

    fun getSelectedIconMode(ctx: Context): String = prefs(ctx).getString("icon_mode", IconMode.SYSTEM.id) ?: IconMode.SYSTEM.id
    fun saveSelectedIconMode(ctx: Context, mode: String) = prefs(ctx).edit().putString("icon_mode", mode).apply()

    fun getHiddenApps(ctx: Context): Set<String> = prefs(ctx).getStringSet("hidden_apps", emptySet()) ?: emptySet()
    fun saveHiddenApps(ctx: Context, set: Set<String>) = prefs(ctx).edit().putStringSet("hidden_apps", set).apply()

    fun loadTiles(ctx: Context): List<TileItem> {
        val raw = prefs(ctx).getString("tiles", null) ?: return defaultTiles()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val customCol = if (o.has("cCol") && !o.isNull("cCol")) o.getLong("cCol") else null
                val customAlp = if (o.has("cAlp") && !o.isNull("cAlp")) o.getDouble("cAlp").toFloat() else null
                TileItem(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    size = try { TileSize.valueOf(o.optString("size", "MEDIUM")) } catch (_: Exception) { TileSize.MEDIUM },
                    packageName = o.optString("pkg", "").ifEmpty { null },
                    specialType = try { TileSpecialType.valueOf(o.optString("spec", "NONE")) } catch (_: Exception) { TileSpecialType.NONE },
                    iconName = o.optString("icon", "apps"),
                    badgeCount = o.optInt("badge", 0),
                    customColorHex = customCol,
                    customAlpha = customAlp
                )
            }.ifEmpty { defaultTiles() }
        } catch (_: Exception) { defaultTiles() }
    }

    fun saveTiles(ctx: Context, list: List<TileItem>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("title", t.title); put("size", t.size.name)
                put("pkg", t.packageName ?: ""); put("spec", t.specialType.name)
                put("icon", t.iconName); put("badge", t.badgeCount)
                if (t.customColorHex != null) put("cCol", t.customColorHex)
                if (t.customAlpha != null) put("cAlp", t.customAlpha.toDouble())
            })
        }
        prefs(ctx).edit().putString("tiles", arr.toString()).apply()
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
        TileItem("clock_w", "Часы", TileSize.WIDE, specialType = TileSpecialType.WIDGET_DIGITAL_CLOCK, iconName = "schedule")
    )
}

object IconPackHelper {
    private val cache = mutableMapOf<String, Map<String, String>>()

    fun getInstalledIconPacks(ctx: Context): List<IconPackInfo> {
        val pm = ctx.packageManager
        val results = mutableListOf<IconPackInfo>()
        val actions = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")
        val seen = mutableSetOf<String>()
        for (act in actions) {
            val resolved = pm.queryIntentActivities(Intent(act), 0)
            for (ri in resolved) {
                val pkg = ri.activityInfo.packageName
                if (seen.add(pkg)) {
                    results.add(
                        IconPackInfo(
                            packageName = pkg,
                            label = ri.loadLabel(pm).toString(),
                            icon = try { ri.loadIcon(pm) } catch (_: Exception) { null }
                        )
                    )
                }
            }
        }
        return results.sortedBy { it.label.lowercase() }
    }

    private fun parseAppFilter(ctx: Context, packPkg: String): Map<String, String> {
        cache[packPkg]?.let { return it }
        val map = mutableMapOf<String, String>()
        try {
            val res = ctx.packageManager.getResourcesForApplication(packPkg)
            val xmlId = res.getIdentifier("appfilter", "xml", packPkg)
            if (xmlId != 0) {
                val parser = res.getXml(xmlId)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "item") {
                        val comp = parser.getAttributeValue(null, "component") ?: ""
                        val drawable = parser.getAttributeValue(null, "drawable") ?: ""
                        if (comp.startsWith("ComponentInfo{") && drawable.isNotEmpty()) {
                            val inside = comp.removePrefix("ComponentInfo{").removeSuffix("}")
                            val pkg = inside.substringBefore("/")
                            if (pkg.isNotEmpty() && !map.containsKey(pkg)) {
                                map[pkg] = drawable
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        } catch (_: Exception) {}
        cache[packPkg] = map
        return map
    }

    fun loadAppBitmap(ctx: Context, targetPkg: String, iconMode: String, sizePx: Int = 112): ImageBitmap? {
        return try {
            val pm = ctx.packageManager
            var drawable: Drawable? = null
            if (iconMode != IconMode.SYSTEM.id && iconMode != IconMode.WHITE_WP.id) {
                val mapping = parseAppFilter(ctx, iconMode)
                val resName = mapping[targetPkg]
                if (!resName.isNullOrEmpty()) {
                    val packRes: Resources = pm.getResourcesForApplication(iconMode)
                    val resId = packRes.getIdentifier(resName, "drawable", iconMode)
                    if (resId != 0) {
                        drawable = ContextCompat.getDrawable(ctx.createPackageContext(iconMode, 0), resId)
                    }
                }
            }
            if (drawable == null) {
                drawable = pm.getApplicationIcon(targetPkg)
            }
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            if (iconMode == IconMode.WHITE_WP.id) {
                val monoBmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                val monoCanvas = Canvas(monoBmp)
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(monoCanvas)
                val paint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(
                        ColorMatrix(
                            floatArrayOf(
                                0f, 0f, 0f, 0f, 255f,
                                0f, 0f, 0f, 0f, 255f,
                                0f, 0f, 0f, 0f, 255f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                }
                canvas.drawBitmap(monoBmp, 0f, 0f, paint)
            } else {
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(canvas)
            }
            bmp.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { WpApp() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WpApp() {
    val ctx = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { 2 })

    var accentHex by remember { mutableLongStateOf(Storage.getAccent(ctx)) }
    var isDark by remember { mutableStateOf(Storage.isDarkTheme(ctx)) }
    var gridCols by remember { mutableIntStateOf(Storage.getGridColumns(ctx)) }
    var wallpaperUriStr by remember { mutableStateOf(Storage.getWallpaperUri(ctx)) }
    var wallpaperMode by remember { mutableStateOf(Storage.getWallpaperMode(ctx)) }
    var globalTileAlpha by remember { mutableFloatStateOf(Storage.getGlobalTileAlpha(ctx)) }
    var appListBlur by remember { mutableFloatStateOf(Storage.getAppListBlur(ctx)) }
    var isAppListGrid by remember { mutableStateOf(Storage.isAppListGrid(ctx)) }
    var appListCols by remember { mutableIntStateOf(Storage.getAppListCols(ctx)) }
    var appIconPadding by remember { mutableFloatStateOf(Storage.getAppIconPadding(ctx)) }
    var selectedIconMode by remember { mutableStateOf(Storage.getSelectedIconMode(ctx)) }
    var hiddenApps by remember { mutableStateOf(Storage.getHiddenApps(ctx)) }

    val accent = Color(accentHex)
    val bgColor = if (isDark) Color.Black else Color.White
    val fgColor = if (isDark) Color.White else Color.Black

    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.Transparent.toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.Transparent.toArgb()
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.isAppearanceLightStatusBars = !isDark && wallpaperUriStr == null
        insetsController.isAppearanceLightNavigationBars = !isDark && wallpaperUriStr == null
    }

    var tiles by remember { mutableStateOf(Storage.loadTiles(ctx)) }
    var allInstalledApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var editingTileId by remember { mutableStateOf<String?>(null) }
    var isEditModeActive by remember { mutableStateOf(false) }
    var customizingTileId by remember { mutableStateOf<String?>(null) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var settingsInitialTab by remember { mutableIntStateOf(0) }
    var showWidgetPicker by remember { mutableStateOf(false) }
    var galleryPhotos by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var wallpaperBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var searchQuery by remember { mutableStateOf("") }

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

    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            wallpaperUriStr = uri.toString()
            Storage.saveWallpaperUri(ctx, uri.toString())
        }
    }

    LaunchedEffect(wallpaperUriStr) {
        wallpaperBitmap = if (wallpaperUriStr != null) {
            withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openInputStream(Uri.parse(wallpaperUriStr))?.use { stream ->
                        BitmapFactory.decodeStream(stream)?.asImageBitmap()
                    }
                } catch (_: Exception) { null }
            }
        } else null
    }

    fun reloadInstalledApps() {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        allInstalledApps = pm.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map {
                val isSys = (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                AppEntry(
                    label = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    icon = try { it.loadIcon(pm) } catch (_: Exception) { null },
                    isSystemApp = isSys
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    LaunchedEffect(Unit) {
        if (!hasMediaPerm) permLauncher.launch(mediaPerm)
        reloadInstalledApps()
    }

    LaunchedEffect(hasMediaPerm) {
        if (hasMediaPerm) galleryPhotos = loadRecentPhotos(ctx, 10)
    }

    LaunchedEffect(pager.currentPage) {
        if (pager.currentPage == 0) {
            searchQuery = ""
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    val visibleApps = remember(allInstalledApps, hiddenApps) {
        allInstalledApps.filterNot { it.packageName in hiddenApps }
    }

    BackHandler(
        enabled = showSettingsScreen || showWidgetPicker || customizingTileId != null ||
            isEditModeActive || searchQuery.isNotEmpty() || pager.currentPage != 0
    ) {
        when {
            showSettingsScreen -> showSettingsScreen = false
            showWidgetPicker -> showWidgetPicker = false
            customizingTileId != null -> customizingTileId = null
            isEditModeActive -> {
                isEditModeActive = false
                editingTileId = null
            }
            searchQuery.isNotEmpty() -> {
                searchQuery = ""
                focusManager.clearFocus()
                keyboardController?.hide()
            }
            pager.currentPage != 0 -> {
                focusManager.clearFocus()
                keyboardController?.hide()
                scope.launch { pager.animateScrollToPage(0) }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .clipToBounds()
    ) {
        if (wallpaperBitmap != null && (wallpaperMode == WallpaperMode.W10_FULLSCREEN || pager.currentPage == 1)) {
            val blurRadius = if (pager.currentPage == 1) appListBlur.dp else 0.dp
            Image(
                bitmap = wallpaperBitmap!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
            )
            if (pager.currentPage == 1) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(if (isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.55f))
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            if (showSettingsScreen) {
                WpSettingsScreen(
                    bgColor = bgColor,
                    fgColor = fgColor,
                    accentColor = accent,
                    currentAccent = accentHex,
                    isDark = isDark,
                    gridCols = gridCols,
                    wallpaperBitmap = wallpaperBitmap,
                    wallpaperMode = wallpaperMode,
                    globalTileAlpha = globalTileAlpha,
                    appListBlur = appListBlur,
                    selectedIconMode = selectedIconMode,
                    hiddenCount = hiddenApps.size,
                    initialSection = settingsInitialTab,
                    onToggleTheme = { dark -> isDark = dark; Storage.saveDarkTheme(ctx, dark) },
                    onSelectAccent = { hex -> accentHex = hex; Storage.saveAccent(ctx, hex) },
                    onSelectCols = { cols -> gridCols = cols; Storage.saveGridColumns(ctx, cols) },
                    onPickWallpaper = {
                        wallpaperPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onClearWallpaper = {
                        wallpaperUriStr = null
                        Storage.saveWallpaperUri(ctx, null)
                    },
                    onSelectWallpaperMode = { m -> wallpaperMode = m; Storage.saveWallpaperMode(ctx, m) },
                    onChangeTileAlpha = { a -> globalTileAlpha = a; Storage.saveGlobalTileAlpha(ctx, a) },
                    onChangeAppListBlur = { b -> appListBlur = b; Storage.saveAppListBlur(ctx, b) },
                    onSelectIconMode = { m -> selectedIconMode = m; Storage.saveSelectedIconMode(ctx, m) },
                    onRestoreHiddenApps = {
                        hiddenApps = emptySet()
                        Storage.saveHiddenApps(ctx, emptySet())
                        Toast.makeText(ctx, "Скрытые приложения восстановлены", Toast.LENGTH_SHORT).show()
                    },
                    onSetDefaultHome = { ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) },
                    onResetTiles = {
                        val d = Storage.defaultTiles()
                        tiles = d
                        Storage.saveTiles(ctx, d)
                        editingTileId = null
                        isEditModeActive = false
                        showSettingsScreen = false
                    },
                    onClose = { showSettingsScreen = false }
                )
            } else {
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = !isEditModeActive,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    if (page == 0) {
                        StartScreen(
                            tiles = tiles,
                            gridCols = gridCols,
                            accent = accent,
                            bgColor = bgColor,
                            fgColor = fgColor,
                            wallpaperBitmap = wallpaperBitmap,
                            wallpaperMode = wallpaperMode,
                            globalTileAlpha = globalTileAlpha,
                            selectedIconMode = selectedIconMode,
                            galleryPhotos = galleryPhotos,
                            isEditModeActive = isEditModeActive,
                            editingTileId = editingTileId,
                            onEnterEditMode = { tileId ->
                                isEditModeActive = true
                                editingTileId = tileId
                            },
                            onExitEditMode = {
                                isEditModeActive = false
                                editingTileId = null
                            },
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
                            onCustomizeTile = { id -> customizingTileId = id },
                            onUnpin = { id ->
                                val updated = tiles.filterNot { it.id == id }
                                tiles = updated
                                Storage.saveTiles(ctx, updated)
                                if (editingTileId == id) editingTileId = null
                            },
                            onOpenWidgetPicker = { showWidgetPicker = true },
                            onOpenWallpaperSettings = {
                                settingsInitialTab = 1
                                showSettingsScreen = true
                            },
                            onOpenAllSettings = {
                                settingsInitialTab = 0
                                showSettingsScreen = true
                            },
                            onClick = { t ->
                                if (t.specialType == TileSpecialType.LAUNCHER_SETTINGS) {
                                    settingsInitialTab = 0
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
                            apps = visibleApps,
                            accent = accent,
                            bgColor = bgColor,
                            fgColor = fgColor,
                            hasWallpaper = wallpaperBitmap != null,
                            isGrid = isAppListGrid,
                            gridCols = appListCols,
                            iconPadding = appIconPadding,
                            selectedIconMode = selectedIconMode,
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onToggleGrid = {
                                isAppListGrid = !isAppListGrid
                                Storage.saveAppListGrid(ctx, isAppListGrid)
                            },
                            onChangeCols = { c ->
                                appListCols = c
                                Storage.saveAppListCols(ctx, c)
                            },
                            onChangeIconPadding = { p ->
                                appIconPadding = p
                                Storage.saveAppIconPadding(ctx, p)
                            },
                            onClickApp = { app ->
                                searchQuery = ""
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                ctx.packageManager.getLaunchIntentForPackage(app.packageName)?.let { ctx.startActivity(it) }
                            },
                            onPinApp = { app ->
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
                                searchQuery = ""
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                scope.launch { pager.animateScrollToPage(0) }
                            },
                            onHideApp = { app ->
                                val next = hiddenApps + app.packageName
                                hiddenApps = next
                                Storage.saveHiddenApps(ctx, next)
                            },
                            onUninstallApp = { app ->
                                val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                                ctx.startActivity(intent)
                            },
                            onOpenAppInfo = { app ->
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${app.packageName}")
                                }
                                ctx.startActivity(intent)
                            },
                            onRefreshApps = { reloadInstalledApps() },
                            onSettings = {
                                settingsInitialTab = 0
                                showSettingsScreen = true
                            }
                        )
                    }
                }
            }
        }

        val targetCustomTile = tiles.firstOrNull { it.id == customizingTileId }
        if (targetCustomTile != null) {
            TileCustomizeDialog(
                tile = targetCustomTile,
                themeAccent = accentHex,
                globalAlpha = globalTileAlpha,
                onApply = { newColor, newAlpha ->
                    val updated = tiles.map {
                        if (it.id == targetCustomTile.id) it.copy(customColorHex = newColor, customAlpha = newAlpha) else it
                    }
                    tiles = updated
                    Storage.saveTiles(ctx, updated)
                    customizingTileId = null
                },
                onDismiss = { customizingTileId = null }
            )
        }

        if (showWidgetPicker) {
            WidgetPickerDialog(
                accent = accent,
                onAddWidget = { newTile ->
                    val updated = tiles + newTile
                    tiles = updated
                    Storage.saveTiles(ctx, updated)
                    showWidgetPicker = false
                },
                onDismiss = { showWidgetPicker = false }
            )
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
            projection, null, null, sortOrder
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
                if (bmp != null) result.add(bmp.asImageBitmap())
            }
        }
    } catch (_: Exception) {}
    result
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
    bgColor: Color,
    fgColor: Color,
    wallpaperBitmap: ImageBitmap?,
    wallpaperMode: WallpaperMode,
    globalTileAlpha: Float,
    selectedIconMode: String,
    galleryPhotos: List<ImageBitmap>,
    isEditModeActive: Boolean,
    editingTileId: String?,
    onEnterEditMode: (String?) -> Unit,
    onExitEditMode: () -> Unit,
    onReorderTiles: (List<TileItem>) -> Unit,
    onCycleSize: (String) -> Unit,
    onCustomizeTile: (String) -> Unit,
    onUnpin: (String) -> Unit,
    onOpenWidgetPicker: () -> Unit,
    onOpenWallpaperSettings: () -> Unit,
    onOpenAllSettings: () -> Unit,
    onClick: (TileItem) -> Unit,
    onAllApps: () -> Unit
) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scrollState = rememberScrollState()

    val sideMargin = 8.dp
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val gridW = screenW - sideMargin * 2
    val gap = if (gridCols == 6) 4.5.dp else 6.dp
    val cell = (gridW - gap * (gridCols - 1)) / gridCols

    val (placed, rows) = remember(tiles, gridCols) { placeTiles(tiles, gridCols) }
    val gridH = if (rows > 0) cell * rows + gap * (rows - 1) else 200.dp

    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragFingerPos by remember { mutableStateOf(Offset.Zero) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(isEditModeActive) {
        if (!isEditModeActive) {
            draggingId = null
            dragFingerPos = Offset.Zero
            dragDelta = Offset.Zero
        }
    }

    val latestPlaced by rememberUpdatedState(placed)
    val latestTiles by rememberUpdatedState(tiles)
    val latestOnReorder by rememberUpdatedState(onReorderTiles)
    val latestOnEnterEdit by rememberUpdatedState(onEnterEditMode)

    val cellStepPx = with(density) { (cell + gap).toPx() }
    val screenWpx = with(density) { gridW.toPx() }.coerceAtLeast(1f)
    val screenHpx = with(density) { gridH.toPx() }.coerceAtLeast(1f)

    val targetTiltY = if (draggingId != null) ((dragDelta.x / screenWpx) * 26f).coerceIn(-14f, 14f) else 0f
    val targetTiltX = if (draggingId != null) ((-dragDelta.y / screenHpx) * 26f).coerceIn(-14f, 14f) else 0f

    val parallaxTiltY by animateFloatAsState(
        targetValue = targetTiltY,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "parallaxY"
    )
    val parallaxTiltX by animateFloatAsState(
        targetValue = targetTiltX,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "parallaxX"
    )

    val textShadow = if (wallpaperBitmap != null) {
        Shadow(color = Color.Black.copy(alpha = 0.85f), offset = Offset(1f, 2f), blurRadius = 6f)
    } else null

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isEditModeActive) {
                    detectTapGestures(
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            latestOnEnterEdit(null)
                        },
                        onTap = {
                            if (isEditModeActive) {
                                draggingId = null
                                dragDelta = Offset.Zero
                                onExitEditMode()
                            }
                        }
                    )
                }
                .verticalScroll(scrollState, enabled = draggingId == null)
                .padding(horizontal = sideMargin, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .width(gridW)
                    .height(gridH)
            ) {
                placed.forEach { p ->
                    key(p.tile.id) {
                        val isEditingThis = isEditModeActive && editingTileId == p.tile.id
                        val isDraggingThis = draggingId == p.tile.id
                        val isBackgroundTile = isEditModeActive && !isDraggingThis && editingTileId != null

                        val wCols = p.tile.size.cols.coerceAtMost(gridCols)
                        val w = cell * wCols + gap * (wCols - 1)
                        val h = cell * p.tile.size.rows + gap * (p.tile.size.rows - 1)

                        val baseX = with(density) { ((cell + gap) * p.col).toPx() }
                        val baseY = with(density) { ((cell + gap) * p.row).toPx() }

                        val parallaxShiftX = if (isBackgroundTile && draggingId != null) -parallaxTiltY * 1.1f else 0f
                        val parallaxShiftY = if (isBackgroundTile && draggingId != null) parallaxTiltX * 1.1f else 0f

                        val currentOffsetX = if (isDraggingThis) dragFingerPos.x else baseX + parallaxShiftX
                        val currentOffsetY = if (isDraggingThis) dragFingerPos.y else baseY + parallaxShiftY

                        val scale by animateFloatAsState(
                            targetValue = when {
                                isDraggingThis -> 1.08f
                                isEditingThis -> 1.03f
                                isEditModeActive -> 0.94f
                                else -> 1f
                            },
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                            label = "tileScale"
                        )

                        val tileEditAlpha by animateFloatAsState(
                            targetValue = when {
                                isDraggingThis || isEditingThis || editingTileId == null -> 1f
                                isEditModeActive -> 0.65f
                                else -> 1f
                            },
                            label = "tileAlpha"
                        )

                        val resolvedColor = Color(p.tile.customColorHex ?: accent.value.toLong())
                        val resolvedSurfaceAlpha = when {
                            wallpaperBitmap == null -> 1f
                            p.tile.customAlpha != null -> p.tile.customAlpha
                            else -> globalTileAlpha
                        }

                        Box(
                            modifier = Modifier
                                .zIndex(if (isDraggingThis) 10f else if (isEditingThis) 5f else 1f)
                                .offset { IntOffset(currentOffsetX.roundToInt(), currentOffsetY.roundToInt()) }
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = tileEditAlpha
                                    if (isBackgroundTile) {
                                        rotationX = parallaxTiltX
                                        rotationY = parallaxTiltY
                                        cameraDistance = 14f * density.density
                                    }
                                }
                                .width(w)
                                .height(h)
                                .clipToBounds()
                                .then(
                                    if (isEditingThis) Modifier.border(2.dp, fgColor)
                                    else if (wallpaperBitmap != null && resolvedSurfaceAlpha < 0.25f) Modifier.border(1.dp, Color.White.copy(alpha = 0.25f))
                                    else Modifier
                                )
                                .pointerInput(p.tile.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            latestOnEnterEdit(p.tile.id)
                                            val startPlaced = latestPlaced.firstOrNull { it.tile.id == p.tile.id }
                                            val sx = ((startPlaced?.col ?: p.col) * cellStepPx)
                                            val sy = ((startPlaced?.row ?: p.row) * cellStepPx)
                                            draggingId = p.tile.id
                                            dragFingerPos = Offset(sx, sy)
                                            dragDelta = Offset.Zero
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragFingerPos += dragAmount
                                            dragDelta += dragAmount

                                            val targetCol = (dragFingerPos.x / cellStepPx).roundToInt().coerceIn(0, gridCols - 1)
                                            val targetRow = (dragFingerPos.y / cellStepPx).roundToInt().coerceAtLeast(0)

                                            val targetPlaced = latestPlaced.firstOrNull { cand ->
                                                cand.tile.id != p.tile.id &&
                                                    targetRow in cand.row until (cand.row + cand.tile.size.rows) &&
                                                    targetCol in cand.col until (cand.col + cand.tile.size.cols.coerceAtMost(gridCols))
                                            }

                                            if (targetPlaced != null) {
                                                val currentList = latestTiles
                                                val fromIdx = currentList.indexOfFirst { it.id == p.tile.id }
                                                val toIdx = currentList.indexOfFirst { it.id == targetPlaced.tile.id }
                                                if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                                                    val mutable = currentList.toMutableList()
                                                    val moved = mutable.removeAt(fromIdx)
                                                    mutable.add(toIdx, moved)
                                                    latestOnReorder(mutable)
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            draggingId = null
                                            dragFingerPos = Offset.Zero
                                            dragDelta = Offset.Zero
                                        },
                                        onDragCancel = {
                                            draggingId = null
                                            dragFingerPos = Offset.Zero
                                            dragDelta = Offset.Zero
                                        }
                                    )
                                }
                                .clickable {
                                    if (isEditModeActive) {
                                        draggingId = null
                                        dragDelta = Offset.Zero
                                        if (editingTileId != p.tile.id) onEnterEditMode(p.tile.id)
                                        else onExitEditMode()
                                    } else {
                                        onClick(p.tile)
                                    }
                                }
                        ) {
                            if (wallpaperBitmap != null && wallpaperMode == WallpaperMode.WP8_INSIDE_TILES) {
                                val scrollShiftPx = scrollState.value * 0.25f
                                Image(
                                    bitmap = wallpaperBitmap,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .requiredWidth(screenW)
                                        .requiredHeight(screenH * 1.15f)
                                        .offset {
                                            IntOffset(
                                                x = (-baseX - with(density) { sideMargin.toPx() }).roundToInt(),
                                                y = (-baseY + scrollShiftPx).roundToInt()
                                            )
                                        }
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(resolvedColor.copy(alpha = resolvedSurfaceAlpha))
                            )

                            when {
                                p.tile.specialType == TileSpecialType.PEOPLE_LIVE -> PeopleTile(p.tile.title, resolvedColor, textShadow)
                                p.tile.specialType == TileSpecialType.CALENDAR_LIVE -> CalendarTile(p.tile.title, p.tile.size, textShadow)
                                p.tile.specialType == TileSpecialType.WIDGET_DIGITAL_CLOCK -> DigitalClockWidget(p.tile.size, textShadow)
                                p.tile.specialType == TileSpecialType.WIDGET_ANALOG_CLOCK -> AnalogClockWidget()
                                p.tile.specialType == TileSpecialType.WIDGET_WEATHER -> WeatherWidget(p.tile.size, textShadow)
                                p.tile.specialType == TileSpecialType.WIDGET_MUSIC -> MusicWidget(textShadow)
                                p.tile.specialType == TileSpecialType.WIDGET_BATTERY -> BatteryWidget(p.tile.size, textShadow)
                                p.tile.specialType == TileSpecialType.WIDGET_MEMORY -> MemoryWidget(p.tile.size, textShadow)
                                p.tile.specialType == TileSpecialType.WIDGET_QUICK_TOGGLES -> QuickTogglesWidget()
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
                                            Text(
                                                op, color = Color.White, fontSize = 13.sp, maxLines = 1,
                                                style = TextStyle(shadow = textShadow),
                                                modifier = Modifier.align(Alignment.BottomStart)
                                            )
                                        }
                                    }
                                }
                                p.tile.specialType == TileSpecialType.STORE_BADGE -> {
                                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ShoppingBag, null, tint = Color.White, modifier = Modifier.size(26.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            p.tile.badgeCount.toString(), color = Color.White, fontSize = 19.sp,
                                            fontWeight = FontWeight.Light, style = TextStyle(shadow = textShadow)
                                        )
                                    }
                                }
                                else -> {
                                    val bmp = remember(p.tile.packageName, selectedIconMode) {
                                        p.tile.packageName?.let { pkg -> IconPackHelper.loadAppBitmap(ctx, pkg, selectedIconMode, 112) }
                                    }
                                    Box(Modifier.fillMaxSize().padding(if (p.tile.size == TileSize.SMALL) 6.dp else 10.dp)) {
                                        if (bmp != null) {
                                            Image(bmp, null, Modifier.size(if (p.tile.size == TileSize.SMALL) 34.dp else 48.dp).align(Alignment.Center))
                                        } else {
                                            Icon(iconFor(p.tile.iconName), null, tint = Color.White, modifier = Modifier.size(if (p.tile.size == TileSize.SMALL) 30.dp else 48.dp).align(Alignment.Center))
                                        }
                                        if (p.tile.size != TileSize.SMALL) {
                                            Text(
                                                p.tile.title, color = Color.White, fontSize = 13.sp, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = textShadow),
                                                modifier = Modifier.align(Alignment.BottomStart)
                                            )
                                        }
                                    }
                                }
                            }

                            if (isEditingThis && !isDraggingThis) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(26.dp)
                                        .background(Color.Black.copy(alpha = 0.85f), CircleShape)
                                        .border(1.5.dp, Color.White, CircleShape)
                                        .clickable { onUnpin(p.tile.id) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Открепить", tint = Color.White, modifier = Modifier.size(15.dp))
                                }

                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(4.dp)
                                        .size(26.dp)
                                        .background(Color.Black.copy(alpha = 0.85f), CircleShape)
                                        .border(1.5.dp, Color.White, CircleShape)
                                        .clickable { onCustomizeTile(p.tile.id) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Цвет и прозрачность", tint = Color.White, modifier = Modifier.size(14.dp))
                                }

                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .size(26.dp)
                                        .background(Color.Black.copy(alpha = 0.85f), CircleShape)
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
            }

            Spacer(Modifier.height(16.dp))

            if (!isEditModeActive) {
                Row(
                    modifier = Modifier
                        .width(gridW)
                        .clickable { onAllApps() }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "все приложения",
                        color = fgColor.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        style = TextStyle(shadow = textShadow),
                        modifier = Modifier.padding(end = 10.dp)
                    )
                    Box(Modifier.size(34.dp).border(1.5.dp, fgColor, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = fgColor, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(76.dp))
        }

        AnimatedVisibility(
            visible = isEditModeActive,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141414))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)))
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                WpBottomBarButton(Icons.Default.Widgets, "+ Виджет", onClick = onOpenWidgetPicker)
                WpBottomBarButton(Icons.Default.Wallpaper, "Обои и тема", onClick = onOpenWallpaperSettings)
                WpBottomBarButton(Icons.Default.Settings, "Настройки", onClick = onOpenAllSettings)
                WpBottomBarButton(Icons.Default.Check, "Готово", tint = accent, onClick = {
                    draggingId = null
                    dragDelta = Offset.Zero
                    onExitEditMode()
                })
            }
        }
    }
}

@Composable
fun WpBottomBarButton(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .border(1.5.dp, tint, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun TileCustomizeDialog(
    tile: TileItem,
    themeAccent: Long,
    globalAlpha: Float,
    onApply: (Long?, Float?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedHex by remember { mutableStateOf(tile.customColorHex) }
    var alphaVal by remember { mutableFloatStateOf(tile.customAlpha ?: globalAlpha) }
    var useCustomAlpha by remember { mutableStateOf(tile.customAlpha != null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .border(1.5.dp, Color.White.copy(alpha = 0.4f))
                .padding(18.dp)
        ) {
            Text(
                text = "Плитка: ${tile.title}",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Light
            )
            Spacer(Modifier.height(14.dp))

            Text("Персональный цвет", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(if (selectedHex == null) Color(themeAccent) else Color.Transparent)
                    .border(1.dp, Color.White.copy(alpha = 0.5f))
                    .clickable { selectedHex = null },
                contentAlignment = Alignment.Center
            ) {
                Text("Как в общей теме", color = Color.White, fontSize = 13.sp)
            }

            Spacer(Modifier.height(8.dp))

            WP_ACCENTS.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { hex ->
                        val isSel = selectedHex == hex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .background(Color(hex))
                                .then(if (isSel) Modifier.border(2.5.dp, Color.White) else Modifier)
                                .clickable { selectedHex = hex }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = useCustomAlpha,
                    onCheckedChange = { useCustomAlpha = it },
                    colors = CheckboxDefaults.colors(checkedColor = Color(themeAccent))
                )
                Text("Своя прозрачность (${(alphaVal * 100).roundToInt()}%)", color = Color.White, fontSize = 14.sp)
            }
            if (useCustomAlpha) {
                Slider(
                    value = alphaVal,
                    onValueChange = { alphaVal = it },
                    valueRange = 0.05f..1f,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(themeAccent))
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Отмена", color = Color.Gray) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onApply(selectedHex, if (useCustomAlpha) alphaVal else null) },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(themeAccent))
                ) {
                    Text("Применить", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun WidgetPickerDialog(
    accent: Color,
    onAddWidget: (TileItem) -> Unit,
    onDismiss: () -> Unit
) {
    val widgetCatalog = listOf(
        Triple("Цифровые часы", TileSize.WIDE, TileSpecialType.WIDGET_DIGITAL_CLOCK),
        Triple("Аналоговые часы", TileSize.MEDIUM, TileSpecialType.WIDGET_ANALOG_CLOCK),
        Triple("Погода и прогноз", TileSize.WIDE, TileSpecialType.WIDGET_WEATHER),
        Triple("Календарь", TileSize.MEDIUM, TileSpecialType.CALENDAR_LIVE),
        Triple("Музыкальный плеер", TileSize.WIDE, TileSpecialType.WIDGET_MUSIC),
        Triple("Заряд батареи", TileSize.MEDIUM, TileSpecialType.WIDGET_BATTERY),
        Triple("ОЗУ и хранилище", TileSize.MEDIUM, TileSpecialType.WIDGET_MEMORY),
        Triple("Быстрые переключатели", TileSize.MEDIUM, TileSpecialType.WIDGET_QUICK_TOGGLES)
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161616))
                .border(1.5.dp, Color.White.copy(alpha = 0.35f))
                .padding(18.dp)
        ) {
            Text("Добавить живую плитку", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(12.dp))

            widgetCatalog.forEach { (title, size, spec) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
