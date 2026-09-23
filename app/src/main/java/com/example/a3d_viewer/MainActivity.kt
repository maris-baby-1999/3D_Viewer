package com.example.a3d_viewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.a3d_viewer.ui.ViewerScreen
import com.example.a3d_viewer.ui.theme._3D_ViewerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            _3D_ViewerTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ViewerScreen()
                }
            }
        }
    }
}
