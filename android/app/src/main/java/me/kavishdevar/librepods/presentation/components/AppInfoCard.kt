/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R

@Composable
fun AppInfoCard(
    navigateToReleaseNotesScreen: (() -> Unit)? = null,
) {
    StyledList(title = stringResource(R.string.about)) {
        StyledListItem(
            name = stringResource(R.string.version),
            description = BuildConfig.VERSION_NAME,
            onClick = navigateToReleaseNotesScreen
        )

        StyledListItem(
            name = stringResource(R.string.version_code),
            description = BuildConfig.VERSION_CODE.toString(),
        )

        StyledListItem(
            name = stringResource(R.string.flavor),
            description = BuildConfig.FLAVOR,
        )

        StyledListItem(
            name = stringResource(R.string.build_type),
            description = BuildConfig.BUILD_TYPE,
        )
    }
}
