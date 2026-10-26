package com.fullmatchshows

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FullMatchShowsPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FullMatchShowsProvider())
    }
}
