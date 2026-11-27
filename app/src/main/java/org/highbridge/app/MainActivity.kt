// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app

import org.highbridge.app.ui.tr

import org.highbridge.app.ui.toast
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import org.highbridge.app.ui.Gap
import org.highbridge.app.ui.Skyline
import org.highbridge.app.ui.screens.*
import org.highbridge.app.ui.theme.StPaulMeshPalette
import org.highbridge.app.ui.theme.StPaulMeshTheme

val LocalNav = staticCompositionLocalOf<NavHostController> { error("No nav") }

class MainActivity : ComponentActivity() {
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val app = application as HighbridgeApp
        setContent {
            val dark by app.prefs.darkMode.collectAsState()
            StPaulMeshTheme(darkTheme = when (dark) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }) {
                var unlocked by remember { mutableStateOf(app.prefs.pinRecord.value == null) }
                if (!unlocked) LockScreen(onUnlocked = { unlocked = true })
                else HighbridgeRoot(pendingRoute.value) { pendingRoute.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("conversation")?.let { pendingRoute.value = "chat/${Uri.encode(it)}" }
        if (i.action == Intent.ACTION_VIEW && i.data?.scheme == "highbridge") pendingRoute.value = "channels?import=${Uri.encode(i.dataString)}"
        if (i.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") pendingRoute.value = "connect"
        i.getStringExtra("route")?.let { pendingRoute.value = it }
    }

    override fun onResume() { super.onResume(); (application as HighbridgeApp).radios.foreground = true }
    override fun onPause() { super.onPause(); (application as HighbridgeApp).radios.foreground = false }
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val tabs = listOf(
    Tab("chats", "Chats", Icons.AutoMirrored.Filled.Chat), Tab("map", "Map", Icons.Filled.Map), Tab("nodes", "Nodes", Icons.Filled.Hub),
    Tab("bridge", "Bridge", Icons.Filled.Link), Tab("radio", "Radio", Icons.Filled.Tune),
)

@Composable
fun HighbridgeRoot(pendingRoute: String?, consumed: () -> Unit) {
    val app = HighbridgeApp.instance
    val ctx = LocalContext.current
    val nav = rememberNavController()
    val onboarded by app.prefs.onboarded.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) app.location.start()
    }
    LaunchedEffect(Unit) {
        val list = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        perms.launch(list.toTypedArray())
        app.radios.toasts.collect { ctx.toast(it, long = true) }
    }
    LaunchedEffect(onboarded) { if (onboarded) runCatching { app.radios.reconnectRemembered() } }

    CompositionLocalProvider(LocalNav provides nav) {
        ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = onboarded, drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Skyline(Modifier.statusBarsPadding().fillMaxWidth().height(40.dp))
                    Text("Highbridge", Modifier.padding(20.dp, 12.dp, 20.dp, 0.dp), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Text("West 7th · Saint Paul", Modifier.padding(horizontal = 20.dp), color = StPaulMeshPalette.SchmidtBrick, style = MaterialTheme.typography.labelMedium)
                    Gap(12)
                    fun go(r: String) { scope.launch { drawer.close() }; nav.navigate(r) }
                    NavigationDrawerItem(label = { Text(tr("Connect a radio")) }, icon = { Icon(Icons.Filled.Bluetooth, null) }, selected = false, onClick = { go("connect") })
                    NavigationDrawerItem(label = { Text(tr("Channels & QR codes")) }, icon = { Icon(Icons.Filled.Key, null) }, selected = false, onClick = { go("channels") })
                    NavigationDrawerItem(label = { Text(tr("Field tools")) }, icon = { Icon(Icons.Filled.Build, null) }, selected = false, onClick = { go("tools") })
                    NavigationDrawerItem(label = { Text("APRS") }, icon = { Icon(Icons.Filled.CellTower, null) }, selected = false, onClick = { go("aprs") })
                    NavigationDrawerItem(label = { Text(tr("USB terminal & flashing")) }, icon = { Icon(Icons.Filled.Usb, null) }, selected = false, onClick = { go("usb") })
                    NavigationDrawerItem(label = { Text(tr("Firmware updates")) }, icon = { Icon(Icons.Filled.SettingsInputAntenna, null) }, selected = false, onClick = { go("updates") })
                    NavigationDrawerItem(label = { Text(tr("Settings")) }, icon = { Icon(Icons.Filled.Settings, null) }, selected = false, onClick = { go("settings") })
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    fun open(url: String) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    NavigationDrawerItem(label = { Text("stpaulmesh.com") }, selected = false, onClick = { open("https://stpaulmesh.com") })
                    NavigationDrawerItem(label = { Text(tr("Meshtastic docs")) }, selected = false, onClick = { open("https://meshtastic.org/docs/") })
                    NavigationDrawerItem(label = { Text("MeshCore") }, selected = false, onClick = { open("https://meshcore.io") })
                    if (org.highbridge.app.rns.ReticulumSupport.AVAILABLE) NavigationDrawerItem(label = { Text(tr("Reticulum manual")) }, selected = false, onClick = { open("https://reticulum.network/manual/") })
                }
            }
        }) {
            val back by nav.currentBackStackEntryAsState()
            val route = back?.destination?.route
            Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
                if (onboarded && tabs.any { it.route == route }) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { t ->
                        NavigationBarItem(selected = route == t.route, onClick = { nav.navigate(t.route) { popUpTo("chats"); launchSingleTop = true } },
                            icon = { Icon(t.icon, null) }, label = { Text(tr(t.label)) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primaryContainer))
                    }
                }
            }) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    val start = remember { if (onboarded) "chats" else "welcome" }
                    NavHost(nav, startDestination = start) {
                        composable("welcome") { WelcomeScreen() }
                        composable("chats") { ChatsScreen(openDrawer = { scope.launch { drawer.open() } }) }
                        composable("chat/{c}") { ChatScreen(Uri.decode(it.arguments?.getString("c") ?: "")) }
                        composable("map") { MapScreen() }
                        composable("nodes") { NodesScreen() }
                        composable("node/{k}") { NodeDetailScreen(Uri.decode(it.arguments?.getString("k") ?: "")) }
                        composable("bridge") { BridgeScreen() }
                        composable("radio") { RadioScreen() }
                        composable("connect") { ConnectScreen() }
                        composable("channels?import={i}") { ChannelsScreen(it.arguments?.getString("i")?.let(Uri::decode)) }
                        composable("channels") { ChannelsScreen(null) }
                        composable("tools") { ToolsScreen() }
                        composable("topology") { TopologyScreen() }
                        composable("compass/{k}") { CompassScreen(Uri.decode(it.arguments?.getString("k") ?: "")) }
                        composable("geofences") { GeofenceScreen() }
                        composable("weather") { WeatherScreen() }
                        composable("morse") { MorseScreen() }
                        composable("atak") { AtakScreen() }
                        composable("tracks") { TracksScreen() }
                        composable("rangetest") { RangeTestScreen() }
                        composable("airtime") { AirtimeScreen() }
                        composable("checkin") { CheckInScreen() }
                        composable("micron") { MicronScreen() }
                        composable("repeater/{id}") { RepeaterAdminScreen(Uri.decode(it.arguments?.getString("id") ?: "")) }
                        composable("usb") { UsbScreen() }
                        composable("updates") { UpdatesScreen() }
                        composable("settings") { SettingsScreen() }
                        composable("licenses") { LicensesScreen() }
                        composable("mtsettings") { org.highbridge.app.ui.screens.MeshtasticSettingsScreen() }
                        composable("mtremote/{n}") { b -> org.highbridge.app.ui.screens.MeshtasticSettingsScreen(Uri.decode(b.arguments?.getString("n") ?: "")) }
                        composable("wardrive") { org.highbridge.app.ui.screens.WardriveScreen() }
                        composable("search") { org.highbridge.app.ui.screens.SearchScreen() }
                        composable("discover") { org.highbridge.app.ui.screens.DiscoverScreen() }
                        composable("predict") { org.highbridge.app.ui.screens.CoveragePredictScreen() }
                        composable("ar") { org.highbridge.app.ui.screens.ArScreen() }
                        composable("aprs") { org.highbridge.app.ui.screens.AprsScreen() }
                        composable("callsign") { org.highbridge.app.ui.screens.CallsignScreen() }
                        composable("callsign/{c}") { b -> org.highbridge.app.ui.screens.CallsignScreen(Uri.decode(b.arguments?.getString("c") ?: "")) }
                    }
                    // After NavHost, so a notification or link that opens the app never navigates before the graph exists.
                    LaunchedEffect(pendingRoute) { pendingRoute?.let { runCatching { nav.navigate(it) }; consumed() } }
                }
            }
        }
    }
}
