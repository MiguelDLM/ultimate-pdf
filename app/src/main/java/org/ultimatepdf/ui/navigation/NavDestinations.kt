package org.ultimatepdf.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.DynamicForm
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestination(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    VIEWER("viewer", "Viewer", Icons.Default.MenuBook),
    EDITOR("editor", "Page Editor", Icons.Default.GridView),
    SCANNER("scanner", "Scan & OCR", Icons.Default.CameraAlt),
    ANNOTATE("annotate", "Annotate", Icons.Default.Draw),
    FORMS("forms", "Forms", Icons.Default.DynamicForm),
    SIGN("sign", "Digital Sign", Icons.Default.EditNote),
    TOOLS("tools", "PDF Tools", Icons.Default.AutoAwesome)
}
