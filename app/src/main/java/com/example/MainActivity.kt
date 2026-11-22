package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.EdgeTunnelApp
import com.example.ui.EdgeTunnelViewModel
import com.example.ui.theme.EdgeTunnelTheme

class MainActivity : ComponentActivity() {

  private val viewModel: EdgeTunnelViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    setContent {
      EdgeTunnelTheme {
        EdgeTunnelApp(viewModel = viewModel)
      }
    }
  }
}
