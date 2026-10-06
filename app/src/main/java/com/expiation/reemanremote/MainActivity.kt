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
            onArmChange = c::setArmed,
            onMove = c::drive,
            onStop = c::stop,
            onDock = c::dock,
            onLoadPoints = c::loadPoints,
            onGoTo = c::goTo,
            onAddPoint = c::addPointHere,
            onGoToCharge = c::goToCharge,
            onDemoEstop = c::demoToggleEstop,
            onDemoWifi = c::demoToggleWifi,
            onDemoBlock = c::demoBlockNextStep,
            onDemoPerson = c::demoPersonInTheWay,
            onDemoChargeOnItsOwn = c::demoChargeOnItsOwn,
            onDemoBattery = c::demoDrainBattery,
            onDemoReset = c::demoReset,
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
        // Safety: leaving the app always disarms. A step already running finishes by itself.
        vm.controller.pause()
    }
}
