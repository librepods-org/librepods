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

package me.kavishdevar.librepods.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

const val TAG = "PlayBillingProvider"

private const val PREMIUM_PRODUCT_ID = "librepods.advanced_features.v2"

class PlayBillingProvider(
    context: Context
) : BillingProvider, PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isPremium = MutableStateFlow(false)
    override val isPremium: StateFlow<Boolean> = _isPremium

    private val _price = MutableStateFlow("unknown")
    override val price: StateFlow<String> = _price


    private var productDetails: ProductDetails? = null

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    init {
        connect()
    }

    private fun connect() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch {
                        queryProductDetails()
                        queryExistingPurchases()
                    }
                } else {
                    Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                connect()
            }
        })
    }

    private suspend fun queryProductDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PREMIUM_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            ).build()

        val result = billingClient.queryProductDetails(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            productDetails = result.productDetailsList?.firstOrNull()
            Log.d(TAG, "Product loaded: ${productDetails?.name}")
            val priceString = productDetails
                ?.oneTimePurchaseOfferDetails
                ?.formattedPrice

            if (priceString != null) {
                _price.value = priceString
            }
        } else {
            Log.w(TAG, "queryProductDetails failed: ${result.billingResult.debugMessage}")
        }
    }

    private suspend fun queryExistingPurchases() {
        val result = billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        processPurchases(result.purchasesList)
    }

    override fun purchase(activity: Activity) {
        val details = productDetails ?: run {
            Log.e(TAG, "Product details not loaded yet")
            return
        }

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            ).build()

        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "launchBillingFlow failed: ${result.debugMessage}")
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.let { processPurchases(it) }
            BillingClient.BillingResponseCode.USER_CANCELED -> Log.d(TAG, "User cancelled")
            else -> Log.w(TAG, "Purchase error ${result.responseCode}: ${result.debugMessage}")
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        val hasPremium = purchases.any {
            it.products.contains(PREMIUM_PRODUCT_ID) &&
                it.purchaseState == Purchase.PurchaseState.PURCHASED
        }

//        val purchase = purchases.find {
//            it.products.contains(PREMIUM_PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED
//        }
//
//        if (purchase != null) {
//            val consumeParams = ConsumeParams.newBuilder()
//                .setPurchaseToken(purchase.purchaseToken)
//                .build()
//            scope.launch {
//                billingClient.consumeAsync(consumeParams) { _, _ ->}
//            }
//        }

        _isPremium.value = hasPremium

        scope.launch {
            purchases
                .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
                .forEach { acknowledge(it) }
        }
    }

    private suspend fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        val result = billingClient.acknowledgePurchase(params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Acknowledgement failed: ${result.debugMessage}")
        }
    }

    override fun queryPurchases() {
        scope.launch {
            queryExistingPurchases()
        }
    }

    override fun restorePurchases() {
        queryPurchases()
    }
}
