package com.lumia.wplauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

enum class TileSize(val cols: Int, val rows: Int) { SMALL(1, 1), MEDIUM(2, 2), WIDE(4, 2) }
enum class TileSpecialType { NONE, PHONE, PEOPLE_LIVE, CALENDAR_LIVE, STORE_BADGE, LAUNCHER_SETTINGS }

data class TileItem(
    val id: String,
    val title: String,
    val size: TileSize,
    val packageName: String? = null,
    val specialType: TileSpecialType = TileSpecialType.NONE,
    val iconName: String = "apps",
    val badgeCount: Int = 0
)

data class AppEntry(val label: String, val packageName: String, val icon: Drawable?)

val WP_ACCENTS = listOf(
    0xFFE51400, 0xFF0050EF, 0xFF008A00, 0xFFF09609,
    0xFFD80073, 0xFF1BA1E2, 0xFFAA00FF, 0xFFA4C400
)

object Storage {
    fun getAccent(ctx: Context): Long =
        ctx.getSharedPreferences("wp8", Context.MODE_PRIVATE).getLong("accent", 0xFFE51400)

    fun saveAccent(ctx: Context, hex: Long) =
        ctx.getSharedPreferences("wp8", Context.MODE_PRIVATE).edit().putLong("accent", hex).apply()

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
        TileItem("office", "Office", TileSize.MEDIUM, iconName = "description"),
        TileItem("calendar", "Календарь", TileSize.WIDE, specialType = TileSpecialType.CALENDAR_LIVE, iconName = "event"),
        TileItem("camera", "Камера", TileSize.MEDIUM, iconName = "photo_camera"),
        TileItem("settings", "Тема Lumia", TileSize.MEDIUM, specialType = TileSpecialType.LAUNCHER_SETTINGS, iconName = "palette")
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WpApp() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WpApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { 2 })
    var accentHex by remember { mutableLongStateOf(Storage.getAccent(ctx)) }
    val accent = Color(accentHex)
    var tiles by remember { mutableStateOf(Storage.loadTiles(ctx)) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var editTile by remember { mutableStateOf<TileItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        apps = pm.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, try { it.loadIcon(pm) } catch (e: Exception) { null }) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    BackHandler(enabled = pager.currentPage != 0) { scope.launch { pager.animateScrollToPage(0) } }

    Box(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            if (page == 0) {
                StartScreen(
                    tiles = tiles,
                    accent = accent,
                    onClick = { t ->
                        if (t.specialType == TileSpecialType.LAUNCHER_SETTINGS) showSettings = true
                        else launchTile(ctx, t)
                    },
                    onLongClick = { editTile = it },
                    onAllApps = { scope.launch { pager.animateScrollToPage(1) } }
                )
            } else {
                AllAppsScreen(
                    apps = apps,
                    accent = accent,
                    onClick = { app -> ctx.packageManager.getLaunchIntentForPackage(app.packageName)?.let { ctx.startActivity(it) } },
                    onPin = { app ->
                        val updated = tiles + TileItem("app_${System.currentTimeMillis()}", app.label, TileSize.MEDIUM, app.packageName)
                        tiles = updated; Storage.saveTiles(ctx, updated)
                        scope.launch { pager.animateScrollToPage(0) }
                    },
                    onSettings = { showSettings = true }
                )
            }
        }

        editTile?.let { target ->
            AlertDialog(
                onDismissRequest = { editTile = null },
                containerColor = Color(0xFF1A1A1A),
                title = { Text(target.title, color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Размер:", color = Color.LightGray)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(TileSize.SMALL to "1×1", TileSize.MEDIUM to "2×2", TileSize.WIDE to "4×2").forEach { (sz, lbl) ->
                                Box(
                                    Modifier.background(if (target.size == sz) accent else Color.DarkGray)
                                        .clickable {
                                            val upd = tiles.map { if (it.id == target.id) it.copy(size = sz) else it }
                                            tiles = upd; Storage.saveTiles(ctx, upd); editTile = null
                                        }.padding(horizontal = 14.dp, vertical = 8.dp)
                                ) { Text(lbl, color = Color.White) }
                            }
                        }
                        TextButton(onClick = {
                            val idx = tiles.indexOfFirst { it.id == target.id }
                            if (idx > 0) {
                                val m = tiles.toMutableList(); val item = m.removeAt(idx); m.add(idx - 1, item)
                                tiles = m; Storage.saveTiles(ctx, m)
                            }
                            editTile = null
                        }) { Text("Поднять выше", color = Color.White) }
                        TextButton(onClick = {
                            val upd = tiles.filterNot { it.id == target.id }
                            tiles = upd; Storage.saveTiles(ctx, upd); editTile = null
                        }) { Text("Открепить", color = Color(0xFFFF5252)) }
                    }
                },
                confirmButton = { TextButton(onClick = { editTile = null }) { Text("Закрыть", color = accent) } }
            )
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                containerColor = Color(0xFF1A1A1A),
                title = { Text("Настройки Lumia WP8", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Цвет плиток:", color = Color.LightGray)
                        WP_ACCENTS.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { hex ->
                                    Box(
                                        Modifier.size(38.dp).background(Color(hex))
                                            .then(if (hex == accentHex) Modifier.border(2.dp, Color.White) else Modifier)
                                            .clickable { accentHex = hex; Storage.saveAccent(ctx, hex) }
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = { ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) },
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Сделать лаунчером по умолчанию", color = Color.White) }
                        OutlinedButton(
                            onClick = {
                                val d = Storage.defaultTiles(); tiles = d; Storage.saveTiles(ctx, d); showSettings = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Сбросить сетку как на Lumia", color = Color.White) }
                    }
                },
                confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Готово", color = Color.White) } }
            )
        }
    }
}

data class PlacedTile(val tile: TileItem, val col: Int, val row: Int)

fun placeTiles(tiles: List<TileItem>): Pair<List<PlacedTile>, Int> {
    val occ = mutableSetOf<Pair<Int, Int>>()
    val res = mutableListOf<PlacedTile>()
    var maxR = 0
    for (t in tiles) {
        var r = 0; var done = false
        while (!done) {
            for (c in 0..(4 - t.size.cols)) {
                val fits = (0 until t.size.rows).all { dr -> (0 until t.size.cols).all { dc -> (r + dr to c + dc) !in occ } }
                if (fits) {
                    for (dr in 0 until t.size.rows) for (dc in 0 until t.size.cols) occ.add(r + dr to c + dc)
                    res.add(PlacedTile(t, c, r))
                    if (r + t.size.rows > maxR) maxR = r + t.size.rows
                    done = true; break
                }
            }
            r++
        }
    }
    return res to maxR
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StartScreen(tiles: List<TileItem>, accent: Color, onClick: (TileItem) -> Unit, onLongClick: (TileItem) -> Unit, onAllApps: () -> Unit) {
    val ctx = LocalContext.current
    val gridW = LocalConfiguration.current.screenWidthDp.dp - 44.dp
    val gap = 7.dp
    val cell = (gridW - gap * 3) / 4
    val (placed, rows) = remember(tiles) { placeTiles(tiles) }
    val gridH = if (rows > 0) cell * rows + gap * (rows - 1) else 200.dp

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, top = 12.dp, bottom = 32.dp)) {
        Box(Modifier.width(gridW).height(gridH)) {
            placed.forEach { p ->
                val w = cell * p.tile.size.cols + gap * (p.tile.size.cols - 1)
                val h = cell * p.tile.size.rows + gap * (p.tile.size.rows - 1)
                Box(
                    Modifier.offset(x = (cell + gap) * p.col, y = (cell + gap) * p.row)
                        .width(w).height(h).background(accent)
                        .combinedClickable(onClick = { onClick(p.tile) }, onLongClick = { onLongClick(p.tile) })
                ) {
                    when (p.tile.specialType) {
                        TileSpecialType.PEOPLE_LIVE -> PeopleTile(p.tile.title, accent)
                        TileSpecialType.CALENDAR_LIVE -> CalendarTile(p.tile.title)
                        TileSpecialType.PHONE -> {
                            val op = remember {
                                try { (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkOperatorName?.ifBlank { "Нет SIM-карты" } ?: "Нет SIM-карты" }
                                catch (e: Exception) { "Нет SIM-карты" }
                            }
                            Box(Modifier.fillMaxSize().padding(10.dp)) {
                                Icon(Icons.Default.Call, null, tint = Color.White, modifier = Modifier.size(52.dp).align(Alignment.Center))
                                Text(op, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart))
                            }
                        }
                        TileSpecialType.STORE_BADGE -> {
                            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ShoppingBag, null, tint = Color.White, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(p.tile.badgeCount.toString(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Light)
                            }
                        }
                        else -> {
                            val bmp = remember(p.tile.packageName) {
                                p.tile.packageName?.let { pkg ->
                                    try {
                                        val d = ctx.packageManager.getApplicationIcon(pkg)
                                        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                                        val c = Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(c); b.asImageBitmap()
                                    } catch (e: Exception) { null }
                                }
                            }
                            Box(Modifier.fillMaxSize().padding(if (p.tile.size == TileSize.SMALL) 6.dp else 10.dp)) {
                                if (bmp != null) Image(bmp, null, Modifier.size(if (p.tile.size == TileSize.SMALL) 34.dp else 48.dp).align(Alignment.Center))
                                else Icon(iconFor(p.tile.iconName), null, tint = Color.White, modifier = Modifier.size(if (p.tile.size == TileSize.SMALL) 32.dp else 52.dp).align(Alignment.Center))
                                if (p.tile.size != TileSize.SMALL) {
                                    Text(p.tile.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart))
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.width(gridW).clickable { onAllApps() }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text("все приложения", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(end = 10.dp))
            Box(Modifier.size(34.dp).border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
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
fun AllAppsScreen(apps: List<AppEntry>, accent: Color, onClick: (AppEntry) -> Unit, onPin: (AppEntry) -> Unit, onSettings: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(apps, q) { if (q.isBlank()) apps else apps.filter { it.label.contains(q, true) } }
    Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Box(Modifier.weight(1f).height(42.dp).background(Color.White).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (q.isEmpty()) Text("поиск...", color = Color.Gray, fontSize = 16.sp)
                BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = Color.Black, fontSize = 16.sp), cursorBrush = SolidColor(accent), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(42.dp).background(accent).clickable { onSettings() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Palette, null, tint = Color.White)
            }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.packageName }) { app ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onClick(app) }, onLongClick = { onPin(app) }).padding(vertical = 4.dp)
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
                    Text(app.label, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Light, maxLines = 1)
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
        "office" -> Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        "calendar" -> Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build())
        "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        else -> Intent(Settings.ACTION_SETTINGS)
    }
    try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i) }
    catch (e: Exception) { Toast.makeText(ctx, "Не найдено: ${tile.title}", Toast.LENGTH_SHORT).show() }
}

fun iconFor(name: String): ImageVector = when (name) {
    "call" -> Icons.Default.Call
    "message" -> Icons.AutoMirrored.Filled.Message
    "language" -> Icons.Default.Language
    "email" -> Icons.Default.Email
    "shopping_bag" -> Icons.Default.ShoppingBag
    "people" -> Icons.Default.People
    "description" -> Icons.Default.Description
    "event" -> Icons.Default.Event
    "photo_camera" -> Icons.Default.PhotoCamera
    "palette" -> Icons.Default.Palette
    else -> Icons.Default.Apps
}
