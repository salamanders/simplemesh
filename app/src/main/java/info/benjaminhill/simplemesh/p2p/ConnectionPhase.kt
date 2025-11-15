package info.benjaminhill.simplemesh.p2p

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

enum class ConnectionPhase(
    val timeout: Duration,
    val phaseOnTimeout: () -> ConnectionPhase?,
    val icon: ImageVector,
    val color: Color,
) {
    DISCOVERED(30.seconds, { ERROR }, Icons.Default.Info, Color.Blue),
    CONNECTING(30.seconds, { ERROR }, Icons.Default.Info, Color.Gray),

    // Up to the local node to send the ping within the timeout and get the pong back
    CONNECTED(1.minutes, { ERROR }, Icons.Default.CheckCircle, Color.Green),
    DISCONNECTED(30.seconds, { ERROR }, Icons.Default.Close, Color.Gray),
    REJECTED(30.seconds, { ERROR }, Icons.Default.Close, Color.Red),
    ERROR(30.seconds, { null }, Icons.Default.Warning, Color.Red)
}