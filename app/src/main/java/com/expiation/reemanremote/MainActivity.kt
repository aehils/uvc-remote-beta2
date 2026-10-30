package com.expiation.reemanremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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

    private val session by lazy { RobotSession.get(application) }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Reminders, and a routine carrying on out of sight, show as notifications. Asked here, not
        // at START: the dialog takes the screen, which would count as leaving the app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val c = session.controller
        val actions = RemoteActions(
            onDemoChange = session::setDemo,
            onPing = session::testConnection,
            onFindRobot = c::findRobot,
            onPickRobot = c::pickRobot,
            onMove = c::drive,
            onHold = c::hold,
            onHoldAlive = c::holdAlive,
            onRelease = c::release,
            onStop = c::stop,
            onLoadPoints = c::loadPoints,
            onGoTo = c::goTo,
            onAddPoint = c::addPointHere,
            onDeletePoint = c::deletePoint,
            onGoToCharger = c::goToCharger,
            onUndock = c::undock,
            onStartDisinfectionNow = c::startDisinfectionNow,
            onStopDisinfection = c::stopDisinfection,
            onSwitchLampsOn = c::switchLampsOn,
            onDismissAlert = c::dismissAlert,
            onUvSettings = session::setUvSettings,
            onLowBattery = session::setLowBattery,
            onRunningSpeed = session::setRunningSpeed,
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
            onSaveTask = session::saveTask,
            onDeleteTask = session::deleteTask,
            onStartTask = c::startTask,
            onStartRoute = c::startRoute,
            onStartRouteTest = c::startRouteTest,
            onPauseTask = c::pauseTask,
            onResumeTask = c::resumeTask,
            onEndTask = c::endTaskNow,
            onDismissTaskReport = c::dismissTaskReport,
            onAnswerDue = session::answerDue,
        )
        setContent {
            val state by c.state.collectAsStateWithLifecycle()
            val due by session.dueRoutine.collectAsStateWithLifecycle()
            val history by session.history.collectAsStateWithLifecycle()
            RemoteTheme {
                RemoteScreen(state, actions, due, history)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        session.resume()
    }

    override fun onPause() {
        super.onPause()
        session.pause()
    }
}
