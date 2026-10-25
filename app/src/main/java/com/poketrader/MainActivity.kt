package com.poketrader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.poketrader.ui.AppNav
import com.poketrader.ui.PokeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            PokeTheme {
                AppNav()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Changes not synced yet go out in the background, and today's backup is made if due (since 1.20).
        if (!isChangingConfigurations) {
            container.sync.onAppBackground()
            container.appScope.launch { container.backups.dailyIfDue() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** A price alert's notification opens its card on the wishlist (since 1.20). */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == com.poketrader.data.AlertNotifier.ACTION_OPEN_WISH) {
            intent.getLongExtra(com.poketrader.data.AlertNotifier.EXTRA_WISH_ID, 0L).takeIf { it > 0 }?.let { container.openWish.value = it }
        }
    }
}
