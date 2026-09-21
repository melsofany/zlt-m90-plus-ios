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

    /**
     * The API instance is cached because it owns the HTTP client, its cookie jar and the
     * discovered interface family. Building a fresh instance per call discarded the session
     * cookie right after login, so every data request came back unauthenticated.
     */
    private val active = AtomicReference<CacheEntry?>()

    data class Selection(
        val host: String,
        val scheme: String,
        val demoScenario: MockRouterApi.Scenario?,
    )

    /** Loads the route table from assets; a malformed file degrades to an empty config. */
    fun loadRoutesConfig(): RouterRoutesConfig = runCatching {
        context.assets.open(RouterRoutesConfig.ASSET_NAME)
            .bufferedReader()
            .use { RouterRoutesConfig.parse(it.readText()) }
    }.getOrDefault(RouterRoutesConfig.EMPTY)

    /** Reusing the live instance for the same selection; a new selection rebuilds it. */
    private data class CacheEntry(
        val selection: Selection,
        val config: RouterRoutesConfig,
        val api: com.zltm90plus.app.data.remote.RouterApiProtocol,
    )

    fun configure(host: String, scheme: String = "http", demoScenario: MockRouterApi.Scenario? = null) {
        val next = Selection(
            host = host.trim().ifBlank { DEFAULT_ROUTER_HOST },
            scheme = if (scheme.equals("https", ignoreCase = true)) "https" else "http",
            demoScenario = demoScenario,
        )
        if (selection.getAndSet(next) != next) active.set(null)
    }

    fun create(): com.zltm90plus.app.data.remote.RouterApiProtocol {
        val current = selection.get() ?: Selection(DEFAULT_ROUTER_HOST, "http", null)
        active.get()?.takeIf { it.selection == current }?.let { return it.api }

        val config = loadRoutesConfig()
        val api = if (current.demoScenario != null) {
            MockRouterApi(scenario = current.demoScenario)
        } else {
            ZltRouterApi(
                config = config,
                sessionStore = sessionStore,
                hostProvider = { current.host },
                schemeProvider = { current.scheme },
            )
        }
        active.set(CacheEntry(current, config, api))
        return api
    }

    /** Drops the cached client so the next call re-reads routes and re-detects the interface. */
    fun invalidate() = active.set(null)

    fun host(): String = selection.get()?.host ?: DEFAULT_ROUTER_HOST
}