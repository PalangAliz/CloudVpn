package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.config.ConfigSerializer
import com.example.model.ProxyConfig
import com.example.model.RoutingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read app name from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("EdgeTunnel", appName)
  }

  @Test
  fun `config serializer round trips json and edgetunnel uri`() {
    val original = ProxyConfig(
      workerUrl = "https://edge-node.example.workers.dev/ws",
      uuid = ProxyConfig.DEFAULT_UUID,
      cleanIp = "104.16.132.229:8443",
      routingMode = RoutingMode.PROXIED_APPS,
      selectedPackages = setOf("com.android.chrome", "org.mozilla.firefox"),
      localProxyPort = 10809,
      enableLocalProxy = true,
    )

    val json = ConfigSerializer.toJson(original)
    val parsedJson = ConfigSerializer.parse(json)
    assertTrue(parsedJson.isSuccess)
    assertEquals(original, parsedJson.getOrNull())

    val uri = ConfigSerializer.toShareUri(original)
    assertTrue(uri.startsWith("edgetunnel://config?"))
    val parsedUri = ConfigSerializer.parse(uri)
    assertTrue(parsedUri.isSuccess)
    assertEquals(original, parsedUri.getOrNull())
  }
}
