package me.kavishdevar.librepods.presentation.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R

@Composable
fun PrivacyPolicyPage(
    onForward: () -> Unit
) {
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier.background(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(42.dp)
        )
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Last updated: 20 June 2026",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Overview",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "LibrePods does not collect, store, sell, or share personal information for advertising, analytics, tracking, or profiling purposes. The app does not include analytics, crash reporting, telemetry, advertising SDKs, or tracking services.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "All information remains on your device unless you explicitly choose to contact me, create a GitHub issue from the app, or make a purchase or sponsorship through a third-party platform.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "Third Party Services",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "LibrePods provides several ways to contact me, including email, Discord, and GitHub Issues.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "Email",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "If you contact me by email, I receive your email address and any information you choose to include in your message. When using the contact form within LibrePods, your email client will open with a pre-filled email address, the subject line and body that you fill out. The body will also include LibrePods version information and device information to help with troubleshooting.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "You can edit or remove any of this information before sending the email.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "Discord",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "The app provides a link to the LibrePods Discord server. If you choose to join the Discord server, you will be subject to Discord's privacy policy.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "I do not receive any information about you from Discord other than what is publicly visible in the Discord server, such as your username, joining date, common servers, and any messages or content you post in the server, unless you choose to share it with me in the Discord server.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "GitHub Issues",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "When creating a GitHub issue through LibrePods, the app will pre-fill the issue form with:",
                style = MaterialTheme.typography.bodyMedium
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    "• LibrePods version name and version code",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• Device manufacturer and model",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• Android build information",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• Installation source (Google Play or GitHub)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Text(
                text = "This information helps diagnose bugs and provide support. No information is sent automatically. The information is only submitted if you choose to create the GitHub issue.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "Payments", style = MaterialTheme.typography.titleLarge
            )

            if (BuildConfig.PLAY_BUILD) {
                Text(
                    text = "Google Play", style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = "When using the version available on Google Play, purchases are processed by Google Play.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "LibrePods verifies the purchase with Google Play on-device, not with a remote server that I control. I do not receive any information about you or your purchase from Google Play. Payment processing is handled entirely by Google Play, and I do not have access to any of your payment information.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    text = "GitHub Sponsors", style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = "When using the FOSS version available on GitHub, the upgrade button links to GitHub Sponsors. If you choose to sponsor LibrePods, your sponsorship is processed by GitHub.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "Your username and country/region are shared with me when you sponsor LibrePods. Depending on your GitHub Sponsors privacy settings, I may also receive your email address.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Text(
                text = "Contact", style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "If you have questions about this privacy policy, please contact me via email at privacy@kavish.xyz.",
                style = MaterialTheme.typography.bodyMedium
            )

            Button(
                onClick = onForward,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.i_agree),
                    style = MaterialTheme.typography.labelMediumEmphasized
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
