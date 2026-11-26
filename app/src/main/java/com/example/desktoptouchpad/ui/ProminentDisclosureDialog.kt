package com.example.desktoptouchpad.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.example.desktoptouchpad.R

@Composable
fun ProminentDisclosureDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF09090E),
        titleContentColor = Color(0xFFCDD6F4),
        textContentColor = Color(0xFF9399B2),
        title = {
            Text(text = stringResource(R.string.disclosure_title))
        },
        text = {
            Text(text = stringResource(R.string.disclosure_body))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.btn_agree),
                    color = Color(0xFF89B4FA)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.btn_decline),
                    color = Color(0xFF7F849C)
                )
            }
        }
    )
}
