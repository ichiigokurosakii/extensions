package com.movy

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovyPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MovyProvider())
    }
}
