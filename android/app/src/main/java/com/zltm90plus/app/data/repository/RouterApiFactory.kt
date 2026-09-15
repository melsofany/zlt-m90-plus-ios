package com.zltm90plus.app.data.repository

import android.content.Context
import com.zltm90plus.app.data.remote.DEFAULT_ROUTER_HOST
import com.zltm90plus.app.data.remote.MockRouterApi
import com.zltm90plus.app.data.remote.RouterRoutesConfig
import com.zltm90plus.app.data.remote.ZltRouterApi
import com.zltm90plus.app.data.session.SecureSessionStore
import java.util.concurrent.atomic.AtomicReference

/**
 * Builds the active [RouterApiProtocol] implementation.
 *
 * Demo mode is never implied: it requires an explicit user toggle, and the resulting API tags
 * every value as estimated so screens carry a visible "sample data" banner.
 */
class RouterApiFactory(
    private val context: Context,
    private val sessionStore: SecureSessionStore,
) {

    private val selection = AtomicReference<Selection?>()

    data class Selection(
        val host: String,
        val demoScenario: MockRouterApi.Scenario?,
    )

    /** Loads the route table from assets; a malformed file degrades to an empty config. */
    fun loadRoutesConfig(): RouterRoutesConfig = runCatching {
        context.assets.open(RouterRoutesConfig.ASSET_NAME)
            .bufferedReader()
            .use { RouterRoutesConfig.parse(it.readText()) }
    }.getOrDefault(RouterRoutesConfig.EMPTY)

    fun configure(host: String, demoScenario: MockRouterApi.Scenario? = null) {
        selection.set(Selection(host.trim().ifBlank { DEFAULT_ROUTER_HOST }, demoScenario))
    }

    fun create(): com.zltm90plus.app.data.remote.RouterApiProtocol {
        val current = selection.get() ?: Selection(DEFAULT_ROUTER_HOST, null)
        return if (current.demoScenario != null) {
            MockRouterApi(scenario = current.demoScenario)
        } else {
            ZltRouterApi(
                config = loadRoutesConfig(),
                sessionStore = sessionStore,
                hostProvider = { current.host },
            )
        }
    }
    fun host(): String = selection.get()?.host ?: DEFAULT_ROUTER_HOST
}