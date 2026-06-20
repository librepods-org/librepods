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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.kavishdevar.librepods.R
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
fun HearingHealthSettings(
    hasPPECapability: Boolean,
    hasHearingAidCapability: Boolean,
    vendorIdHook: Boolean,
    navigateToHearingProtection: () -> Unit,
    navigateToHearingAid: () -> Unit
) {
    val shouldShowHearingAid = hasHearingAidCapability && vendorIdHook

    if (hasPPECapability && shouldShowHearingAid) {
        StyledList(title = stringResource(R.string.hearing_health)) {
            StyledListItem(
                name = stringResource(R.string.hearing_protection),
                onClick = navigateToHearingProtection
            )

            StyledListItem(
                name = stringResource(R.string.hearing_aid),
                onClick = navigateToHearingAid
            )
        }
    } else if (shouldShowHearingAid) {
        StyledListItem(
            name = stringResource(R.string.hearing_aid),
            onClick = navigateToHearingAid
        )
    } else if (hasPPECapability) {
        StyledListItem(
            title = stringResource(R.string.hearing_health),
            name = stringResource(R.string.hearing_protection),
            onClick = navigateToHearingProtection
        )
    }
}
