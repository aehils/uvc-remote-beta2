package com.expiation.reemanremote

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expiation.reemanremote.ui.RemoteActions
import com.expiation.reemanremote.ui.RemoteScreen
import com.expiation.reemanremote.ui.RemoteTheme

/**
 * Robot Control, beta. The screen only draws [RemoteState] and forwards taps;
 * every drive rule lives in [DriveController].
 */
class MainActivity : ComponentActivity() {

    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val c = vm.controller
        val actions = RemoteActions(
            onDemoChange = vm::setDemo,
            onPing = vm::testConnection,
            onMove = c::drive,
            onHold = c::hold,
            onHoldAlive = c::holdAlive,
            onRelease = c::release,
            onStop = c::stop,
            onLoadPoints = c::loadPoints,
            onGoTo = c::goTo,
            onAddPoint = c::addPointHere,
            onGoToCharger = c::goToCharger,
            onUndock = c::undock,
            onStartDisinfectionNow = c::startDisinfectionNow,
            onStopDisinfection = c::stopDisinfection,
            onSwitchLampsOn = c::switchLampsOn,
            onDismissAlert = c::dismissAlert,
            onUvSettings = vm::setUvSettings,
            onDemoEstop = c::demoToggleEstop,
            onDemoWifi = c::demoToggleWifi,
            onDemoBlock = c::demoBlockNextStep,
            onDemoPerson = c::demoPersonInTheWay,
            onDemoChargeOnItsOwn = c::demoChargeOnItsOwn,
            onDemoBattery = c::demoDrainBattery,
            onDemoReset = c::demoReset,
            onMapVisible = c::setMapVisible,
            onLoadMaps = c::loadMaps,
            onStartMapping = c::startMapping,
            onSaveMap = c::saveMap,
            onDiscardMapping = c::discardMapping,
            onUseMap = c::useMap,
            onRenameMap = c::renameMap,
            onDeleteMap = c::deleteMap,
            onRestoreMap = c::restoreMap,
            onSetPosition = c::setPosition,
            onAddChargingPile = c::addChargingPileHere,
            onAddWall = c::addWall,
            onRemoveWall = c::removeWall,
            onSaveTask = vm::saveTask,
            onDeleteTask = vm::deleteTask,
            onStartTask = c::startTask,
            onStartRoute = c::startRoute,
            onRetryTaskStop = c::retryTaskStop,
            onSkipTaskStop = c::skipTaskStop,
            onEndTask = c::endTaskNow,
            onDismissTaskReport = c::dismissTaskReport,
        )
        setContent {
            val state by c.state.collectAsStateWithLifecycle()
            RemoteTheme {
                RemoteScreen(state, actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.controller.resume()
    }

    override fun onPause() {
        super.onPause()
        // Leaving the app stops polling and switches the UV lamps off. A step already running
        // finishes by itself.
        vm.controller.pause()
    }
}
