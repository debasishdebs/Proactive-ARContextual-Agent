package com.rokid.context.phone.ui.shell

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rokid.context.core.ui.components.Banner
import com.rokid.context.core.ui.components.BannerTone
import com.rokid.context.core.ui.components.PillNav
import com.rokid.context.core.ui.components.PillNavItem
import com.rokid.context.core.ui.theme.PcColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val TABS = listOf(
    PillNavItem(Routes.Tab.HOME.route, Routes.Tab.HOME.label, Icons.Outlined.Home),
    PillNavItem(Routes.Tab.ACTIVITY.route, Routes.Tab.ACTIVITY.label, Icons.Outlined.Timeline),
    PillNavItem(Routes.Tab.SETTINGS.route, Routes.Tab.SETTINGS.label, Icons.Outlined.Settings),
)

/**
 * spec §2.1: NavHost + floating 3-tab PillNav (Live Fit AppActivity layout). [bluetoothMissing] drives a non-blocking
 * hint above the nav (the app works without the permission; only the glasses link needs it).
 */
@Composable
fun AppShell(
    deps: PhoneDeps,
    startRoute: String,
    nav: NavHostController = rememberNavController(),
    bluetoothMissing: StateFlow<Boolean> = MutableStateFlow(false),
) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showNav = Routes.showsNav(route)
    val btMissing by bluetoothMissing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Content inset = measured height of the bottom bar (hint + PillNav + system nav bar), so neither ever covers the
    // end of a scrolled list; 84 dp until the first measure.
    var bottomBarPx by remember { mutableIntStateOf(0) }
    val bottomInset = if (bottomBarPx > 0) with(LocalDensity.current) { bottomBarPx.toDp() } else 84.dp
    Box(Modifier.fillMaxSize().background(PcColors.Surface)) {
        NavHost(nav, startDestination = startRoute, modifier = if (showNav) Modifier.padding(bottom = bottomInset) else Modifier) {
            phoneDestinations(deps, nav)
        }
        if (showNav) {
            Column(
                Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomBarPx = it.height }.navigationBarsPadding().padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (btMissing) {
                    Banner(
                        BannerTone.AMBER, BluetoothPermission.HINT,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag(BluetoothPermission.TAG),
                        actionLabel = "Allow",
                        onAction = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                }
                PillNav(
                    items = TABS,
                    selectedRoute = Routes.tabFor(route)?.route.orEmpty(),
                    onSelect = { tab ->
                        nav.navigate(tab) {
                            popUpTo(Routes.HOME) { saveState = false }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}
