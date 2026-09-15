package com.skypie0102.githubbckp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.skypie0102.githubbckp.ui.HomeScreen
import com.skypie0102.githubbckp.ui.theme.GithubBckpTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GithubBckpTheme {
                HomeScreen()
            }
        }
    }
}
