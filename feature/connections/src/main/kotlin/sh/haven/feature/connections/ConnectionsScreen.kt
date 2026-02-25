package sh.haven.feature.connections

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.ssh.SshSessionManager.SessionState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectionsScreen(
    onNavigateToTerminal: (profileId: String) -> Unit = {},
    viewModel: ConnectionsViewModel = hiltViewModel(),
) {
    val connections by viewModel.connections.collectAsState()
    val sshKeys by viewModel.sshKeys.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val connectingId by viewModel.connectingId.collectAsState()
    val error by viewModel.error.collectAsState()
    val navigateToTerminal by viewModel.navigateToTerminal.collectAsState()
    val deploySuccess by viewModel.deploySuccess.collectAsState()
    val sessionSelection by viewModel.sessionSelection.collectAsState()

    LaunchedEffect(navigateToTerminal) {
        navigateToTerminal?.let { profileId ->
            onNavigateToTerminal(profileId)
            viewModel.onNavigated()
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var connectingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var deployingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var quickConnectText by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(deploySuccess) {
        if (deploySuccess) {
            snackbarHostState.showSnackbar("SSH key deployed successfully")
            viewModel.dismissDeploySuccess()
        }
    }

    if (showAddDialog) {
        ConnectionEditDialog(
            onDismiss = { showAddDialog = false },
            onSave = { profile ->
                viewModel.saveConnection(profile)
                showAddDialog = false
            },
        )
    }

    editingProfile?.let { profile ->
        ConnectionEditDialog(
            existing = profile,
            onDismiss = { editingProfile = null },
            onSave = { updated ->
                viewModel.saveConnection(updated)
                editingProfile = null
            },
        )
    }

    connectingProfile?.let { profile ->
        PasswordDialog(
            profile = profile,
            hasKeys = sshKeys.isNotEmpty(),
            onDismiss = { connectingProfile = null },
            onConnect = { password ->
                viewModel.connect(profile, password)
                connectingProfile = null
            },
        )
    }

    deployingProfile?.let { profile ->
        DeployKeyDialog(
            profile = profile,
            keys = sshKeys,
            onDismiss = { deployingProfile = null },
            onDeploy = { keyId, password ->
                viewModel.deployKey(profile, keyId, password)
                deployingProfile = null
            },
        )
    }

    sessionSelection?.let { selection ->
        SessionPickerDialog(
            managerLabel = selection.managerLabel,
            sessionNames = selection.sessionNames,
            onSelect = { name -> viewModel.onSessionSelected(selection.profileId, name) },
            onNewSession = { viewModel.onSessionSelected(selection.profileId, null) },
            onDismiss = { viewModel.dismissSessionPicker() },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Connections") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add connection")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Quick connect bar
            OutlinedTextField(
                value = quickConnectText,
                onValueChange = { quickConnectText = it },
                placeholder = { Text("Quick connect: user@host:port") },
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val profile = viewModel.parseQuickConnect(quickConnectText)
                            if (profile != null) {
                                viewModel.saveConnection(profile)
                                connectingProfile = profile
                                quickConnectText = ""
                            }
                        },
                        enabled = quickConnectText.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Cable, contentDescription = "Connect")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        val profile = viewModel.parseQuickConnect(quickConnectText)
                        if (profile != null) {
                            viewModel.saveConnection(profile)
                            connectingProfile = profile
                            quickConnectText = ""
                        }
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (connections.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(connections, key = { it.id }) { profile ->
                        val session = sessions[profile.id]
                        ConnectionListItem(
                            profile = profile,
                            session = session,
                            isConnecting = connectingId == profile.id,
                            hasKeys = sshKeys.isNotEmpty(),
                            onTap = {
                                if (session?.status == SessionState.Status.CONNECTED) {
                                    onNavigateToTerminal(profile.id)
                                } else {
                                    connectingProfile = profile
                                }
                            },
                            onEdit = { editingProfile = profile },
                            onDelete = { viewModel.deleteConnection(profile.id) },
                            onDisconnect = { viewModel.disconnect(profile.id) },
                            onDeployKey = { deployingProfile = profile },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectionListItem(
    profile: ConnectionProfile,
    session: SessionState?,
    isConnecting: Boolean,
    hasKeys: Boolean,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDisconnect: () -> Unit,
    onDeployKey: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        ListItem(
            headlineContent = { Text(profile.label) },
            supportingContent = {
                Text("${profile.username}@${profile.host}:${profile.port}")
            },
            leadingContent = {
                when {
                    isConnecting -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    session?.status == SessionState.Status.RECONNECTING -> CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    session?.status == SessionState.Status.CONNECTED -> Icon(
                        Icons.Filled.Circle,
                        contentDescription = "Connected",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(12.dp),
                    )
                    session?.status == SessionState.Status.ERROR -> Icon(
                        Icons.Filled.Circle,
                        contentDescription = "Error",
                        tint = Color(0xFFF44336),
                        modifier = Modifier.size(12.dp),
                    )
                    else -> Icon(
                        Icons.Filled.Circle,
                        contentDescription = "Disconnected",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(12.dp),
                    )
                }
            },
            modifier = Modifier.combinedClickable(
                onClick = onTap,
                onLongClick = { showMenu = true },
            ),
        )

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                onClick = { showMenu = false; onEdit() },
            )
            if (session?.status == SessionState.Status.CONNECTED) {
                DropdownMenuItem(
                    text = { Text("Disconnect") },
                    leadingIcon = { Icon(Icons.Filled.LinkOff, null) },
                    onClick = { showMenu = false; onDisconnect() },
                )
            }
            if (hasKeys) {
                DropdownMenuItem(
                    text = { Text("Deploy SSH Key") },
                    leadingIcon = { Icon(Icons.Filled.VpnKey, null) },
                    onClick = { showMenu = false; onDeployKey() },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { showMenu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Cable,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "No connections yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Tap + to add a server, or type user@host above",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SessionPickerDialog(
    managerLabel: String,
    sessionNames: List<String>,
    onSelect: (String) -> Unit,
    onNewSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$managerLabel sessions") },
        text = {
            Column {
                sessionNames.forEach { name ->
                    ListItem(
                        headlineContent = { Text(name) },
                        modifier = Modifier.clickable { onSelect(name) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                ListItem(
                    headlineContent = {
                        Text(
                            "New session",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.clickable { onNewSession() },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
