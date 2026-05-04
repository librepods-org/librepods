package me.kavishdevar.librepods.presentation.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.billing.BillingManager

data class PurchaseUiState(
    val isPremium: Boolean = false,
    val price: String = ""
)

class PurchaseViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(PurchaseUiState())
    val uiState = _uiState.asStateFlow()

    init {
        observeBilling()
    }

    private fun observeBilling() {
        viewModelScope.launch {
            BillingManager.provider.isPremium.collect { premium ->
                _uiState.update { it.copy(isPremium = premium) }
            }
        }
        viewModelScope.launch {
            BillingManager.provider.price.collect { price ->
                _uiState.update { it.copy(price = price) }
            }
        }
    }

    fun purchase(context: Context) {
        BillingManager.provider.purchase(context as Activity)
    }

    fun restorePurchases() {
        BillingManager.provider.restorePurchases()
    }
}
