package dev.heartline.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.ui.Navigator
import dev.heartline.ui.components.MutedText
import kotlinx.coroutines.delay

/** One text box that keeps whatever is typed: ideas for the app, things to fix, anything. Saved as you type. */
@Composable
fun NotesScreen(controller: HeartlineController, nav: Navigator) {
    val saved by controller.notes.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf<String?>(null) }
    // Start from what's saved, once it has loaded.
    LaunchedEffect(saved) { if (text == null) text = saved }
    // Save a moment after typing stops.
    LaunchedEffect(text) { val t = text ?: return@LaunchedEffect; delay(600); if (t != saved) controller.saveNotes(t) }
    Screen("Notes", onBack = { text?.let { if (it != saved) controller.saveNotes(it) }; nav.pop() }) {
        OutlinedTextField(text.orEmpty(), { text = it }, Modifier.fillMaxWidth().heightIn(min = 320.dp),
            placeholder = { MutedText("Ideas, bugs, things to change…") })
        Gap(8)
        MutedText("Saved on this phone as you type.")
    }
}
