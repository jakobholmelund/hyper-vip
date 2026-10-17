package io.github.jakobholmelund.hypervip.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HyperVipTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    var picking by rememberSaveable { mutableStateOf(false) }
    if (picking) {
        BackHandler { picking = false }
        PickerScreen(onBack = { picking = false })
    } else {
        HomeScreen(onAddVips = { picking = true })
    }
}
