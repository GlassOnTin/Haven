package sh.haven.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import sh.haven.core.data.db.entities.ConnectionProfile

@Composable
fun ConnectionEditDialog(
    existing: ConnectionProfile? = null,
    onDismiss: () -> Unit,
    onSave: (ConnectionProfile) -> Unit,
) {
    var connectionType by remember { mutableStateOf(existing?.connectionType ?: "SSH") }
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf(existing?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var destinationHash by remember { mutableStateOf(existing?.destinationHash ?: "") }
    var rnsHost by remember { mutableStateOf(existing?.reticulumHost ?: "127.0.0.1") }
    var rnsPort by remember { mutableStateOf(existing?.reticulumPort?.toString() ?: "37428") }

    val isEdit = existing != null
    val title = if (isEdit) "Edit Connection" else "New Connection"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // Type selector
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterChip(
                        selected = connectionType == "SSH",
                        onClick = { connectionType = "SSH" },
                        label = { Text("SSH") },
                    )
                    FilterChip(
                        selected = connectionType == "RETICULUM",
                        onClick = { connectionType = "RETICULUM" },
                        label = { Text("Reticulum") },
                    )
                }
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = {
                        Text(if (connectionType == "SSH") "My Server" else "My Node")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                if (connectionType == "SSH") {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host") },
                        placeholder = { Text("192.168.1.1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            placeholder = { Text("root") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter { c -> c.isDigit() } },
                            label = { Text("Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = destinationHash,
                        onValueChange = {
                            destinationHash = it.filter { c -> c in "0123456789abcdefABCDEF" }
                                .take(32)
                        },
                        label = { Text("Destination Hash") },
                        placeholder = { Text("32-character hex") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = rnsHost,
                            onValueChange = { rnsHost = it },
                            label = { Text("Gateway Host") },
                            placeholder = { Text("127.0.0.1") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = rnsPort,
                            onValueChange = { rnsPort = it.filter { c -> c.isDigit() } },
                            label = { Text("Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            val canSave = if (connectionType == "SSH") {
                host.isNotBlank() && username.isNotBlank()
            } else {
                destinationHash.length == 32 && rnsHost.isNotBlank()
            }
            TextButton(
                onClick = {
                    val portInt = port.toIntOrNull() ?: 22
                    val profile = if (connectionType == "SSH") {
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = host,
                            port = portInt,
                            username = username,
                        )).copy(
                            label = label.ifBlank { "$username@$host" },
                            host = host,
                            port = portInt,
                            username = username,
                            connectionType = "SSH",
                            destinationHash = null,
                        )
                    } else {
                        val rnsPortInt = rnsPort.toIntOrNull() ?: 37428
                        (existing ?: ConnectionProfile(
                            label = label,
                            host = "",
                            port = 0,
                            username = "",
                        )).copy(
                            label = label.ifBlank { "RNS:${destinationHash.take(12)}" },
                            host = "",
                            port = 0,
                            username = "",
                            connectionType = "RETICULUM",
                            destinationHash = destinationHash.lowercase(),
                            reticulumHost = rnsHost,
                            reticulumPort = rnsPortInt,
                        )
                    }
                    onSave(profile)
                },
                enabled = canSave,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
