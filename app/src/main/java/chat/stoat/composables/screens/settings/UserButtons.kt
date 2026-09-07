package chat.stoat.composables.screens.settings

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import chat.stoat.R
import chat.stoat.api.StoatAPI
import chat.stoat.api.routes.user.blockUser
import chat.stoat.api.routes.user.openDM
import chat.stoat.api.routes.user.unblockUser
import chat.stoat.callbacks.Action
import chat.stoat.callbacks.ActionChannel
import chat.stoat.core.model.schemas.User
import chat.stoat.internals.Platform
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

@Composable
fun UserButtons(
    user: User,
    dismissSheet: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var botEasterEgg by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    if (user.id == null) return

    val isSelf = user.id == StoatAPI.selfId || user.relationship == "User"

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelf) {
            Button(
                onClick = {
                    scope.launch {
                        ActionChannel.send(Action.TopNavigate("settings/profile"))
                        dismissSheet()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.user_info_sheet_edit_profile))
            }
        } else if (user.relationship == "Blocked") {
            Button(
                onClick = {
                    scope.launch {
                        try {
                            unblockUser(user.id!!)
                        } catch (e: Exception) {
                            if (e.message == "NoEffect") return@launch
                            logcat(LogPriority.ERROR) { e.asLog() }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.user_info_sheet_unblock))
            }
        } else if (user.bot != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    8.dp,
                    alignment = Alignment.Start
                ),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .animateContentSize()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { botEasterEgg = true }
                    .padding(8.dp)
                    .weight(1f)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_smart_toy_24dp),
                    contentDescription = null
                )
                Text(
                    if (botEasterEgg) {
                        stringResource(R.string.user_info_sheet_user_is_bot_easter_egg)
                    } else {
                        stringResource(R.string.user_info_sheet_user_is_bot)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            // Direct Message button for all colleagues in company workspace
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        val dm = openDM(user.id!!)
                        if (dm.id != null) {
                            if (StoatAPI.channelCache[dm.id] == null)
                                StoatAPI.channelCache[dm.id!!] = dm
                            ActionChannel.send(Action.SwitchChannel(dm.id!!))
                            dismissSheet()
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.user_info_sheet_failed_to_open_dm),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.user_info_sheet_send_message))
            }
        }

        if (!isSelf) {
            Row {
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    if (user.relationship != "Blocked") {
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.user_info_sheet_block))
                            },
                            onClick = {
                                scope.launch {
                                    try {
                                        blockUser(user.id!!)
                                    } catch (e: Exception) {
                                        if (e.message == "NoEffect") return@launch
                                        logcat(LogPriority.ERROR) { e.asLog() }
                                    }
                                }
                            }
                        )
                    }

                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.user_info_sheet_copy_id))
                        },
                        onClick = {
                            scope.launch {
                                clipboard.setText(AnnotatedString(user.id!!))
                            }
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.user_info_sheet_report))
                        },
                        onClick = {
                            scope.launch {
                                ActionChannel.send(Action.ReportUser(user.id!!))

                                if (Platform.needsShowClipboardNotification()) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.copied),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                }

                IconButton(
                    onClick = {
                        menuOpen = true
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert_24dp),
                        contentDescription = stringResource(R.string.menu)
                    )
                }
            }
        }
    }
}
