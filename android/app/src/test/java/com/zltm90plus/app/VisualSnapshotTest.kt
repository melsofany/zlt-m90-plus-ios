package com.zltm90plus.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zltm90plus.app.data.model.BatteryStatus
import com.zltm90plus.app.data.model.ChargingState
import com.zltm90plus.app.data.model.DataPlanStatus
import com.zltm90plus.app.data.model.DataSource
import com.zltm90plus.app.data.model.DeviceSnapshot
import com.zltm90plus.app.data.model.RouterDeviceInfo
import com.zltm90plus.app.ui.DashboardUiState
import com.zltm90plus.app.ui.screens.BatteryCard
import com.zltm90plus.app.ui.screens.ConnectScreen
import com.zltm90plus.app.ui.screens.DataPlanCard
import com.zltm90plus.app.ui.screens.NetworkCard
import com.zltm90plus.app.ui.theme.ZltTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real composables with Robolectric's native graphics pipeline, captures actual
 * pixels, and writes PNGs to `app/build/reports/screenshots/`.
 *
 * These tests answer questions text assertions cannot: is the Arabic UI actually painted in both
 * themes, does the layout follow the RTL direction, and does text grow with the system font scale
 * instead of being clipped.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VisualSnapshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val dark: MutableState<Boolean> = mutableStateOf(false)
    private val direction: MutableState<LayoutDirection> = mutableStateOf(LayoutDirection.Rtl)
    private val fontScale: MutableState<Float> = mutableStateOf(1f)

    private val snapshot = DeviceSnapshot(
        deviceInfo = RouterDeviceInfo(model = "ZLT M90 Plus", firmwareVersion = "1.0.0"),
        battery = BatteryStatus(
            percent = 78,
            chargingState = ChargingState.DISCHARGING,
            source = DataSource.ROUTER,
        ),
        plan = DataPlanStatus(
            totalBytes = 50_000_000_000L,
            usedBytes = 21_000_000_000L,
            source = DataSource.MANUAL,
        ),
    )

    @Test
    fun dashboardIsVisibleInBothLightAndDarkMode() {
        host { DashboardContent() }

        dark.value = false
        val light = capture("dashboard-light")
        dark.value = true
        val darkImage = capture("dashboard-dark")

        assertHasVisibleInk(light, "لوحة الوضع الفاتح")
        assertHasVisibleInk(darkImage, "لوحة الوضع الداكن")
        assert(meanDifference(light, darkImage) > 12.0) {
            "الوضع الفاتح والداكن متطابقان تقريبًا؛ يبدو أن الألوان لا تتبدل."
        }
    }

    @Test
    fun rtlLayoutDirectionIsAppliedToTheArabicConnectForm() {
        host { ConnectScreenHost() }

        direction.value = LayoutDirection.Ltr
        val ltr = capture("connect-ltr")
        direction.value = LayoutDirection.Rtl
        val rtl = capture("connect-rtl")

        assertHasVisibleInk(rtl, "شاشة الاتصال باتجاه RTL")
        assert(meanDifference(ltr, rtl) > 1.0) {
            "اتجاه RTL لم يغيّر التخطيط؛ يبدو أن الاتجاه لا يُطبَّق."
        }

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(INTRO_TEXT)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        assert(layouts.isNotEmpty()) { "لم يتمكن الاختبار من قراءة تخطيط النص." }
        assert(layouts.first().layoutInput.layoutDirection == LayoutDirection.Rtl) {
            "النص العربي لم يُخطَّط باتجاه RTL."
        }
    }

    @Test
    fun textGrowsWithTheSystemFontScale() {
        host { BatteryCard(snapshot = snapshot, estimate = null) }

        fontScale.value = 1f
        val normal = textHeight("78٪")
        fontScale.value = 2f
        val enlarged = textHeight("78٪")

        assert(enlarged > normal) {
            "لم يتغير ارتفاع النص مع تكبير الخط: $normal ثم $enlarged"
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp-xhdpi")
    fun compactPhoneRendersEveryCard() {
        host { DashboardContent() }
        assertHasVisibleInk(capture("compact-phone-dashboard"), "لوحة شاشة صغيرة")
    }

    @Test
    @Config(qualifiers = "w480dp-h1000dp-xxhdpi")
    fun largePhoneRendersEveryCard() {
        host { DashboardContent() }
        assertHasVisibleInk(capture("large-phone-dashboard"), "لوحة شاشة كبيرة")
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Registers the composition once; variants are driven by state so setContent is called once. */
    private fun host(content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides direction.value,
                LocalDensity provides Density(density = 1f, fontScale = fontScale.value),
            ) {
                ZltTheme(darkTheme = dark.value) { content() }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun DashboardContent() {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            BatteryCard(snapshot = snapshot, estimate = null)
            NetworkCard(snapshot = snapshot)
            DataPlanCard(plan = snapshot.plan, onSetupClick = {})
        }
    }

    @Composable
    private fun ConnectScreenHost() {
        ConnectScreen(
            state = DashboardUiState(),
            onHostChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onConnect = {},
            onDiscover = {},
            onEnableDemo = {},
            onDismissMessage = {},
        )
    }

    private fun textHeight(text: String): Int {
        composeRule.waitForIdle()
        return composeRule.onNodeWithText(text).fetchSemanticsNode().size.height
    }

    private fun capture(name: String): Bitmap {
        composeRule.waitForIdle()
        val view = composeRule.activity.findViewById<View>(android.R.id.content)
        val bitmap = renderToBitmap(view)
        val directory = File("build/reports/screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }

    /**
     * Robolectric has no real window, so `captureToImage()` cannot work here. Drawing the laid-out
     * view hierarchy straight into a bitmap produces the same pixels the user would see.
     */
    private fun renderToBitmap(view: View): Bitmap {
        if (view.width == 0 || view.height == 0) {
            val width = View.MeasureSpec.makeMeasureSpec(ROOT_WIDTH_PX, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(ROOT_HEIGHT_PX, View.MeasureSpec.EXACTLY)
            view.measure(width, height)
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * Fails when a screen renders as a flat colour, which is exactly how hidden or unpainted text
     * looks. A blank screen is ~100% background; a real one has glyph edges everywhere.
     */
    private fun assertHasVisibleInk(bitmap: Bitmap, label: String) {
        val argb = bitmap.asImageBitmap().toPixelMap().buffer
        val counts = mutableMapOf<Int, Int>()
        var index = 0
        while (index < argb.size) {
            counts[argb[index]] = (counts[argb[index]] ?: 0) + 1
            index += 37
        }
        val sampled = counts.values.sum()
        val background = counts.maxByOrNull { it.value }?.value ?: sampled
        val inkFraction = (sampled - background).toDouble() / sampled
        assert(inkFraction > 0.01) {
            "$label لم تُرسم فعليًا؛ نسبة البكسلات غير الخلفية = $inkFraction"
        }
    }

    private fun meanDifference(first: Bitmap, second: Bitmap): Double {
        assert(first.width == second.width && first.height == second.height) {
            "اختلاف أبعاد الصورتين: ${first.width}x${first.height} مقابل ${second.width}x${second.height}"
        }
        val a = first.asImageBitmap().toPixelMap().buffer
        val b = second.asImageBitmap().toPixelMap().buffer
        var total = 0L
        var count = 0
        var index = 0
        while (index < a.size) {
            total += channelDifference(a[index], b[index])
            count += 3
            index += 13
        }
        return total.toDouble() / count
    }

    private fun channelDifference(pixelA: Int, pixelB: Int): Long {
        var sum = 0L
        for (shift in intArrayOf(0, 8, 16)) {
            val a = (pixelA shr shift) and 0xFF
            val b = (pixelB shr shift) and 0xFF
            sum += kotlin.math.abs(a - b)
        }
        return sum
    }

    private companion object {
        const val INTRO_TEXT =
            "لإدارة الجهاز، يجب أن يكون هاتفك متصلًا بشبكة Wi-Fi الخاصة بـ ZLT M90 Plus."
        const val ROOT_WIDTH_PX = 1080
        const val ROOT_HEIGHT_PX = 2280
    }
}