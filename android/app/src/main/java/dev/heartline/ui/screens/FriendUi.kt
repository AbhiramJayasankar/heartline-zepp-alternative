package dev.heartline.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.theme.HL

/** Where a friend's recording is kept out of, said the same way everywhere it can be set. */
internal const val LEFT_OUT_OF = "your days, trends, readiness, Health Connect and the assistant's answers"

/** A small outlined label on a list row: this one was recorded on someone else. */
@Composable
internal fun FriendTag(modifier: Modifier = Modifier) {
    val c = HL.colors
    Text("Friend", style = HL.type.label, color = c.inkSoft,
        modifier = modifier.semantics { contentDescription = "Recorded on a friend" }
            .border(1.dp, c.line, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
}

/** The switch on a start screen: someone else is about to wear the watch. */
@Composable
internal fun ForFriendToggle(what: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    ToggleRow("For a friend", "Someone else is wearing the watch. The $what is kept and labelled, but left out of $LEFT_OUT_OF.", checked, onChange = onChange)

/**
 * The foot of a result page: whose recording this is, and deleting it. [lockedBy] says why the switch can't be turned
 * off here (the time belongs to another friend mark, a capture say).
 */
@Composable
internal fun WhoseSection(what: String, friend: Boolean, onFriend: (Boolean) -> Unit, deleteLabel: String, onDelete: () -> Unit, lockedBy: String? = null) {
    Gap(28)
    SectionTitle("Whose is this")
    Gap(4)
    Column {
        ToggleRow("A friend's $what", lockedBy ?: if (friend) "Recorded on someone else: kept and labelled, but left out of $LEFT_OUT_OF."
            else "Turn on if someone else wore the watch. It stays here, labelled, and leaves $LEFT_OUT_OF.",
            friend, enabled = lockedBy == null, onChange = onFriend)
    }
    Gap(12)
    PillButton(deleteLabel, onDelete, style = ButtonStyle.QUIET)
    Gap(20)
}

/** Asks before deleting, naming what goes and what stays. */
@Composable
internal fun ConfirmDelete(title: String, body: String, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val c = HL.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface,
        title = { Text(title, style = HL.type.section, color = c.ink) },
        text = { MutedText(body) },
        confirmButton = { TextButton(onDelete) { Text("Delete", color = c.pulse) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = c.inkSoft) } },
    )
}
