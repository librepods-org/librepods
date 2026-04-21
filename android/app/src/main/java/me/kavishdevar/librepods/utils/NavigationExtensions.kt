/*
    LibrePods - AirPods liberated from Apple's ecosystem
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

package me.kavishdevar.librepods.utils

import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder

/**
 * Navigation debounce manager to prevent rapid repeated navigation.
 */
private object NavigationDebounce {
    private var lastNavigationTime: Long = 0
    private var lastRoute: String? = null
    private const val DEBOUNCE_INTERVAL_MS = 300L

    fun shouldNavigate(route: String): Boolean {
        val currentTime = System.currentTimeMillis()
        if (route == lastRoute && currentTime - lastNavigationTime < DEBOUNCE_INTERVAL_MS) {
            return false
        }
        lastNavigationTime = currentTime
        lastRoute = route
        return true
    }
}

/**
 * Navigate with debounce protection.
 * Rapid clicks to the same route within 300ms will be ignored.
 */
fun NavController.navigateDebounced(route: String): Boolean {
    if (!NavigationDebounce.shouldNavigate(route)) {
        return false
    }
    navigate(route)
    return true
}

/**
 * Navigate with debounce protection and custom options.
 */
fun NavController.navigateDebounced(route: String, builder: NavOptionsBuilder.() -> Unit): Boolean {
    if (!NavigationDebounce.shouldNavigate(route)) {
        return false
    }
    navigate(route, builder)
    return true
}

/**
 * Safely pop the back stack with boundary check.
 * Returns false if there's no page to go back to.
 */
fun NavController.popBackStackSafely(): Boolean {
    // Check if there's a previous destination to go back to
    if (previousBackStackEntry != null) {
        return popBackStack()
    }
    return false
}
