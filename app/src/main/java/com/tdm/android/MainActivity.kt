package com.tdm.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TwitchMinerTheme {
                StageOneScreen()
            }
        }
    }
}

@Composable
fun TwitchMinerTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFF9146FF),
        background = Color(0xFF0E0E10),
        surface = Color(0xFF18181B),
        onPrimary = Color.White,
        onBackground = Color(0xFFEFEFF1),
        onSurface = Color(0xFFEFEFF1)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun StageOneScreen() {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E10)),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Twitch Drops Miner",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFBF94FF)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Этап 1: Каркас собран успешно!",
                        fontSize = 15.sp,
                        color = Color(0xFFEFEFF1)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Следующий шаг: подключение входа в Twitch через WebView и ядра майнера.",
                        fontSize = 13.sp,
                        color = Color(0xFFADADB8)
                    )
                }
            }
        }
    }
}
