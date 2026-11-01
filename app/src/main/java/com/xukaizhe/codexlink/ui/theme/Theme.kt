package com.xukaizhe.codexlink.ui.theme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
private val Light=lightColorScheme(primary=Color(0xFF175F4D),onPrimary=Color.White,primaryContainer=Color(0xFFD8F0E4),onPrimaryContainer=Color(0xFF123C30),secondary=Color(0xFF58675F),background=Color(0xFFF6F8F5),surface=Color(0xFFFFFFFF),surfaceVariant=Color(0xFFEBF0EA),onBackground=Color(0xFF17251E),onSurface=Color(0xFF17251E),outline=Color(0xFF89968D))
private val Dark=darkColorScheme(primary=Color(0xFF91D6B4),onPrimary=Color(0xFF103929),primaryContainer=Color(0xFF244D3B),onPrimaryContainer=Color(0xFFC9EBD7),background=Color(0xFF101A15),surface=Color(0xFF18251D),surfaceVariant=Color(0xFF26362B),onBackground=Color(0xFFE1EBE3),onSurface=Color(0xFFE1EBE3),outline=Color(0xFF819187))
@Composable fun CodexLinkTheme(content: @Composable () -> Unit){MaterialTheme(colorScheme=if(isSystemInDarkTheme())Dark else Light,typography=Typography,content=content)}
