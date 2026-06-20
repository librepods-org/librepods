package me.kavishdevar.librepods.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme

@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center
    ) {
        CircularWavyProgressIndicator(
            modifier = Modifier
                .size(120.dp)
        )
    }
}

@Preview
@Composable
fun LoadingScreenPreview() {
    LibrePodsTheme {
        LoadingScreen()
    }
}
