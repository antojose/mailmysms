package com.antolabs.mailmysms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

data class SimInfo(val slotIndex: Int, val carrierName: String?)

object SimUtils {

    fun infoForSubscriptionId(context: Context, subId: Int): SimInfo? {
        if (subId < 0) return null
        return withSubscriptionManager(context) { subManager ->
            val info = subManager.getActiveSubscriptionInfo(subId) ?: return@withSubscriptionManager null
            SimInfo(info.simSlotIndex, info.carrierName?.toString())
        }
    }

    fun infoForPhoneAccountId(context: Context, phoneAccountId: String?): SimInfo? {
        if (phoneAccountId.isNullOrBlank()) return null
        return withSubscriptionManager(context) { subManager ->
            val match = subManager.activeSubscriptionInfoList?.firstOrNull {
                it.subscriptionId.toString() == phoneAccountId || it.iccId == phoneAccountId
            } ?: return@withSubscriptionManager null
            SimInfo(match.simSlotIndex, match.carrierName?.toString())
        }
    }

    private fun <T> withSubscriptionManager(context: Context, block: (SubscriptionManager) -> T?): T? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            val subManager = context.getSystemService(SubscriptionManager::class.java) ?: return null
            block(subManager)
        } catch (e: SecurityException) {
            null
        }
    }
}
