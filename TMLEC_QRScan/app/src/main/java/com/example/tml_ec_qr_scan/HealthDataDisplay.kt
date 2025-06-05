package com.example.tml_ec_qr_scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Live health data overlay component for tracking screens Shows current heart rate and SpO₂ with
 * emojis
 */
@Composable
fun HealthDataDisplay(healthData: HealthData, modifier: Modifier = Modifier) {
    Card(
            modifier = modifier.padding(8.dp).wrapContentSize(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Heart Rate
            Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = "❤️", fontSize = 16.sp)
                Text(
                        text = healthData.getHeartRateDisplay(),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                )
            }

            // SpO₂
            Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = "🫁", fontSize = 16.sp)
                Text(
                        text = healthData.getSpO2Display(),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Session health data summary component for results screen Shows session averages with health
 * status interpretation
 */
@Composable
fun SessionHealthDataDisplay(sessionHealthData: HealthData, modifier: Modifier = Modifier) {
    Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors =
                    CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                    text = "Session Health Summary",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Health metrics
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Average Heart Rate
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "❤️", fontSize = 24.sp)
                    Text(
                            text = "Average HR",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Text(
                            text = sessionHealthData.getHeartRateDisplay(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                    )
                }

                // Average SpO₂
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "🫁", fontSize = 24.sp)
                    Text(
                            text = "Average SpO₂",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Text(
                            text = sessionHealthData.getSpO2Display(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Health Status
            if (sessionHealthData.isValid()) {
                Divider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                )

                Text(
                        text = "Status: ${sessionHealthData.getHealthStatus()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = getHealthStatusColor(sessionHealthData.getHealthStatus()),
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Get color based on health status */
@Composable
private fun getHealthStatusColor(status: String): Color {
    return when {
        status.contains("Low") || status.contains("High") -> MaterialTheme.colorScheme.error
        status.contains("Normal") -> Color(0xFF2E7D32) // Green
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** Compact health data display for smaller spaces */
@Composable
fun CompactHealthDataDisplay(healthData: HealthData, modifier: Modifier = Modifier) {
    Row(
            modifier =
                    modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
                text = "❤️ ${healthData.getHeartRateDisplay()}",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
        )

        Text(
                text = "🫁 ${healthData.getSpO2Display()}",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
        )
    }
}
