package com.byanr.veena.android.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byanr.veena.android.data.Language
import com.byanr.veena.android.data.LANGUAGES
import com.byanr.veena.android.ui.theme.AccentBlue
import com.byanr.veena.android.ui.theme.veenaColors

/**
 * Language selection used both for first-run onboarding and later changes
 * from Settings.
 */
@Composable
fun LanguagePickerScreen(
    title: String,
    subtitle: String,
    selectedCode: String?,
    onPick: (Language) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val colors = veenaColors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        if (onBack != null) {
            Text(
                "Back",
                color = AccentBlue,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(vertical = 6.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            title,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = colors.text,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            fontSize = 14.sp,
            color = colors.textSecondary,
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(18.dp))

        LazyColumn {
            items(LANGUAGES, key = { it.code }) { language ->
                LanguageRow(
                    language = language,
                    selected = language.code == selectedCode,
                    onClick = { onPick(language) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun LanguageRow(
    language: Language,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = veenaColors
    Surface(
        color = if (selected) colors.backgroundSelected else colors.backgroundElement,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(language.name, fontSize = 16.sp, color = colors.text)
                Text(
                    language.nativeName,
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                )
            }
            if (selected) {
                Box(
                    Modifier
                        .size(24.dp)
                        .background(AccentBlue, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = colors.textSecondary,
                )
            }
        }
    }
}
