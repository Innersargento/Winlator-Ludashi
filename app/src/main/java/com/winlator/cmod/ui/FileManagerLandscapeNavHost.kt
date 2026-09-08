package com.winlator.cmod.ui

import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.winlator.cmod.MainActivity
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.ui.theme.createControllerComposeView

object FileManagerLandscapeNavHost {
    @JvmStatic
    fun create(activity: MainActivity): ComposeView = createControllerComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            WinZTheme {
                LandscapeMainNavigation(
                    activity = activity,
                    selected = 0,
                    title = "File Manager"
                )
            }
        }
    }
}
