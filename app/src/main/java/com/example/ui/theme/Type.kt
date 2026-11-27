package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Set of Material typography styles to start with
val Typography =
  Typography(
    headlineMedium =
      TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Black,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 1.5.sp,
      ),
    titleLarge =
      TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Black,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.5.sp,
      ),
    titleMedium =
      TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.25.sp,
      ),
    titleSmall =
      TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
      ),
    bodyLarge =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.25.sp,
      ),
    bodyMedium =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.15.sp,
      ),
    bodySmall =
      TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 9.5.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.1.sp,
      ),
    labelLarge =
      TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.3.sp,
      ),
    labelMedium =
      TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.25.sp,
      ),
    labelSmall =
      TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 8.5.sp,
        lineHeight = 11.sp,
        letterSpacing = 0.2.sp,
      ),
  )
