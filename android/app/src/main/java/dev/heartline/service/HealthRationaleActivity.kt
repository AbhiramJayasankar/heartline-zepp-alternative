package dev.heartline.service

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.screens.Screen
import dev.heartline.ui.theme.HeartlineTheme

class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HeartlineTheme {
            Screen(title = "Health Connect privacy", onBack = { finish() }) {
                MutedText("When you enable export, Heartline writes your watch's heart rate, steps, sleep and sleep stages, blood oxygen, daily resting heart rate, workouts, distance and workout calories to Health Connect. Heart checks can also provide HRV RMSSD and breathing rate. Wrist skin temperature is included when your Health Connect provider supports it.")
                Spacer(Modifier.height(20.dp))
                MutedText("Heartline requests write permissions only. It does not read other apps' Health Connect records. The first export and manual exports cover the last 30 days. After successful watch syncs, incremental exports include recent corrections. Stable record identifiers prevent duplicates when an export is retried.")
                Spacer(Modifier.height(20.dp))
                MutedText("Your original measurements remain in Heartline's local database. This export sends them to the Health Connect store on this Android device. Other apps can use exported records only with the access you grant them in Health Connect.")
                Spacer(Modifier.height(20.dp))
                MutedText("You can disable export in Heartline or revoke individual permissions in Health Connect at any time. Disabling export does not remove previously exported records. Use Health Connect's data controls to delete those records and manage access. Heartline does not upload these exports to a Heartline server.")
                Spacer(Modifier.height(20.dp))
                MutedText("Watch and heart-check measurements are wellness estimates, not a diagnosis. Sleep stages, HRV and breathing estimates may be incomplete or inaccurate.")
            }
        } }
    }
}
