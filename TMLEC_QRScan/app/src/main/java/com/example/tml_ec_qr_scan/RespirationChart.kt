package com.example.tml_ec_qr_scan

// import androidx.compose.ui.semantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlin.math.abs

// New function to smooth the respiratory data
fun smoothRespiratoryData(
        data: List<RespiratoryDataPoint>,
        windowSize: Int = 5
): List<RespiratoryDataPoint> {
        if (data.size <= windowSize) return data

        val smoothedData = mutableListOf<RespiratoryDataPoint>()

        // Using a simple moving average for velocity values
        for (i in data.indices) {
                val start = maxOf(0, i - windowSize / 2)
                val end = minOf(data.size - 1, i + windowSize / 2)
                val windowPoints = data.subList(start, end + 1)

                val avgVelocity = windowPoints.map { it.velocity }.average().toFloat()

                // Create a new data point with the smoothed velocity but keeping all other original
                // values
                val original = data[i]
                smoothedData.add(
                        RespiratoryDataPoint(
                                timestamp = original.timestamp,
                                position = original.position,
                                qrId = original.qrId,
                                movement = original.movement,
                                breathingPhase = original.breathingPhase,
                                amplitude = original.amplitude,
                                velocity = avgVelocity
                        )
                )
        }

        return smoothedData
}

@Composable
fun RespirationChart(
        respiratoryData: List<RespiratoryDataPoint>,
        modifier: Modifier = Modifier,
        maxPoints: Int = 1000,
        id: String = "chart_container"
) {
        if (respiratoryData.isEmpty()) {
                Box(modifier = modifier.background(Color.Black.copy(alpha = 0.3f)).testTag(id)) {
                        Text(
                                "Waiting for respiratory data...",
                                color = Color.White,
                                modifier = Modifier.align(Alignment.Center)
                        )
                }
                return
        }

        val density = LocalDensity.current

        // State for horizontal scrolling
        val scrollState = rememberScrollState()
        val coroutineScope = rememberCoroutineScope()

        // State for canvas size
        var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

        // Apply smoothing to respiratory data
        val smoothedData =
                remember(respiratoryData) {
                        smoothRespiratoryData(
                                respiratoryData,
                                7
                        ) // Increased window size for stronger smoothing
                }

        // Define colors for different breathing phases
        val inhaleColor = Color(0xFF81C784) // Green for inhaling
        val exhaleColor = Color(0xFF64B5F6) // Blue for exhaling
        val pauseColor = Color(0xFFFFD54F) // Amber for pause

        // Calculate how many points to fit into 4 seconds (changed from 2 seconds)
        val firstTimestamp = respiratoryData.firstOrNull()?.timestamp ?: 0L

        // Find point at roughly 4 seconds in (changed from 2 seconds)
        val fourSecondsLater = firstTimestamp + 4000 // 4 seconds in milliseconds
        val pointAt4Sec =
                respiratoryData.indexOfFirst { it.timestamp >= fourSecondsLater }.let {
                        if (it <= 0) respiratoryData.size.coerceAtMost(120) else it
                }

        // This represents how many data points are in 4 seconds of recording (changed from 2
        // seconds)
        val pointsIn4Seconds = pointAt4Sec.coerceAtLeast(60) // Ensure at least 60 points

        // Instead of calculating point spacing based on density, we'll make the chart fixed width
        // and let the scrolling handle the rest
        val pointSpacing = 7.dp // Reduced from 10dp to make points closer together

        // Calculate total width - make the first 4 seconds of data fill exactly the visible area
        val totalPointsWidth = with(density) { pointSpacing * respiratoryData.size }

        // Calculate the width needed for first 4 seconds of data
        val fourSecondWidth = with(density) { pointSpacing * pointsIn4Seconds }

        Column(modifier = modifier.background(Color.Black.copy(alpha = 0.5f)).testTag(id)) {
                // Chart area - make it taller for better visibility
                Row(modifier = Modifier.fillMaxWidth().height(350.dp)) {
                        // Left side - fixed Y-axis labels that don't scroll
                        Box(modifier = Modifier.width(50.dp).fillMaxSize()) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                        val height = size.height
                                        val middleY = height / 2

                                        // Draw the center line (0)
                                        drawLine(
                                                color = Color.LightGray.copy(alpha = 0.6f),
                                                start = Offset(0f, middleY),
                                                end = Offset(size.width, middleY),
                                                strokeWidth = 2f
                                        )

                                        // Draw Y-axis label - vertical text
                                        drawContext.canvas.nativeCanvas.apply {
                                                val textPaint =
                                                        android.graphics.Paint().apply {
                                                                color = android.graphics.Color.WHITE
                                                                textAlign =
                                                                        android.graphics.Paint.Align
                                                                                .CENTER
                                                                textSize = 24f
                                                        }
                                                // Save canvas state
                                                save()
                                                // Rotate canvas for vertical text - positioning
                                                // improved
                                                rotate(-90f, 25f, height / 2)
                                                drawText(
                                                        "Velocity (m/s)",
                                                        25f,
                                                        height / 2 + 10f,
                                                        textPaint
                                                )
                                                // Restore canvas to original state
                                                restore()
                                        }

                                        // Find max velocity for scaling
                                        val maxVelocity =
                                                respiratoryData
                                                        .maxOfOrNull { abs(it.velocity) }
                                                        ?.coerceAtLeast(10f)
                                                        ?: 10f
                                        val velocityStep = 10f // 10 units of velocity
                                        val yPerVelocity = (height / 2) / maxVelocity

                                        // Draw positive velocity values
                                        var velocity = velocityStep
                                        while (velocity <= maxVelocity) {
                                                val y = middleY - (velocity * yPerVelocity)

                                                // Draw label
                                                drawContext.canvas.nativeCanvas.apply {
                                                        val textPaint =
                                                                android.graphics.Paint().apply {
                                                                        color =
                                                                                android.graphics
                                                                                        .Color.WHITE
                                                                        textAlign =
                                                                                android.graphics
                                                                                        .Paint.Align
                                                                                        .RIGHT
                                                                        textSize = 24f
                                                                }
                                                        drawText(
                                                                "+${velocity.toInt()}",
                                                                45f,
                                                                y + 8f,
                                                                textPaint
                                                        )
                                                }
                                                velocity += velocityStep
                                        }

                                        // Draw negative velocity values
                                        velocity = -velocityStep
                                        while (velocity >= -maxVelocity) {
                                                val y = middleY - (velocity * yPerVelocity)

                                                // Draw label
                                                drawContext.canvas.nativeCanvas.apply {
                                                        val textPaint =
                                                                android.graphics.Paint().apply {
                                                                        color =
                                                                                android.graphics
                                                                                        .Color.WHITE
                                                                        textAlign =
                                                                                android.graphics
                                                                                        .Paint.Align
                                                                                        .RIGHT
                                                                        textSize = 24f
                                                                }
                                                        drawText(
                                                                "${velocity.toInt()}",
                                                                45f,
                                                                y + 8f,
                                                                textPaint
                                                        )
                                                }
                                                velocity -= velocityStep
                                        }

                                        // Draw center line label (0)
                                        drawContext.canvas.nativeCanvas.apply {
                                                val textPaint =
                                                        android.graphics.Paint().apply {
                                                                color = android.graphics.Color.WHITE
                                                                textAlign =
                                                                        android.graphics.Paint.Align
                                                                                .RIGHT
                                                                textSize = 24f
                                                        }
                                                drawText("0", 45f, middleY + 8f, textPaint)
                                        }
                                }
                        }

                        // Right side - scrollable chart
                        Box(
                                modifier =
                                        Modifier.weight(1f).fillMaxSize().onSizeChanged { size ->
                                                canvasSize = size.toSize()
                                        }
                        ) {
                                // Force the initial view to show exactly 4 seconds of data
                                // by making the content box width match the visible width
                                Box(
                                        modifier =
                                                Modifier.fillMaxSize().horizontalScroll(scrollState)
                                ) {
                                        val recentData =
                                                if (respiratoryData.size > maxPoints) {
                                                        respiratoryData.takeLast(maxPoints)
                                                } else {
                                                        respiratoryData
                                                }

                                        // Calculate the maximum width we need - at least
                                        // fourSecondWidth
                                        // This ensures we fill the screen with 4 seconds of data
                                        val canvasWidth =
                                                with(density) {
                                                        val containerWidth = canvasSize.width
                                                        if (containerWidth > 0) {
                                                                // Make each 4-second segment
                                                                // exactly fill the screen width
                                                                val pointsPerScreenWidth =
                                                                        pointsIn4Seconds
                                                                val totalScreens =
                                                                        respiratoryData.size
                                                                                .toFloat() /
                                                                                pointsPerScreenWidth
                                                                (containerWidth * totalScreens)
                                                                        .coerceAtLeast(
                                                                                containerWidth
                                                                        )
                                                        } else {
                                                                totalPointsWidth.toPx()
                                                        }
                                                }

                                        // Canvas draws the chart content with width calculated to
                                        // fit exactly 4 seconds per screen
                                        Canvas(
                                                modifier =
                                                        Modifier.width(
                                                                        with(density) {
                                                                                canvasWidth.toDp()
                                                                        }
                                                                )
                                                                .fillMaxSize()
                                        ) {
                                                val width = size.width
                                                val height = size.height
                                                val middleY = height / 2

                                                // Find min/max for scaling
                                                val maxVelocity =
                                                        recentData
                                                                .maxOfOrNull { abs(it.velocity) }
                                                                ?.coerceAtLeast(10f)
                                                                ?: 10f

                                                // Draw horizontal midline (0 velocity line)
                                                drawLine(
                                                        color = Color.White.copy(alpha = 0.5f),
                                                        start = Offset(0f, middleY),
                                                        end = Offset(width, middleY),
                                                        strokeWidth = 1.dp.toPx()
                                                )

                                                // Draw horizontal velocity grid lines
                                                val velocityStep = 10f // 10 units of velocity
                                                val yPerVelocity = (height / 2) / maxVelocity

                                                // Draw positive velocity lines (above middle)
                                                var velocity = velocityStep
                                                while (velocity <= maxVelocity) {
                                                        val y = middleY - (velocity * yPerVelocity)
                                                        drawLine(
                                                                color =
                                                                        Color.White.copy(
                                                                                alpha = 0.2f
                                                                        ),
                                                                start = Offset(0f, y),
                                                                end = Offset(width, y),
                                                                strokeWidth = 0.5.dp.toPx()
                                                        )
                                                        velocity += velocityStep
                                                }

                                                // Draw negative velocity lines (below middle)
                                                velocity = -velocityStep
                                                while (velocity >= -maxVelocity) {
                                                        val y = middleY - (velocity * yPerVelocity)
                                                        drawLine(
                                                                color =
                                                                        Color.White.copy(
                                                                                alpha = 0.2f
                                                                        ),
                                                                start = Offset(0f, y),
                                                                end = Offset(width, y),
                                                                strokeWidth = 0.5.dp.toPx()
                                                        )
                                                        velocity -= velocityStep
                                                }

                                                // Map each point to its X position
                                                // Distribute points so exactly 4 seconds fits in
                                                // the screen width
                                                if (recentData.size > 1) {
                                                        // Calculate point positions - ensure
                                                        // exactly 4 seconds fits in the screen
                                                        // width
                                                        val pointsPerScreenWidth = pointsIn4Seconds
                                                        val screenWidth = canvasSize.width

                                                        val pointPositions =
                                                                recentData.mapIndexed { index, point
                                                                        ->
                                                                        // Calculate how many
                                                                        // screens from the start
                                                                        val screenPosition =
                                                                                index.toFloat() /
                                                                                        pointsPerScreenWidth
                                                                        // Position within the
                                                                        // screen
                                                                        val x =
                                                                                screenPosition *
                                                                                        screenWidth
                                                                        val y =
                                                                                middleY -
                                                                                        (point.velocity *
                                                                                                yPerVelocity)
                                                                        Triple(point, x, y)
                                                                }

                                                        // Draw timestamp markers every 4 seconds
                                                        val firstTimestamp =
                                                                recentData.firstOrNull()?.timestamp
                                                                        ?: 0L
                                                        val lastTimestamp =
                                                                recentData.lastOrNull()?.timestamp
                                                                        ?: 0L
                                                        val secondMarkerIntervalMs =
                                                                4000 // 4 seconds

                                                        // Draw X-axis label
                                                        drawContext.canvas.nativeCanvas.apply {
                                                                val textPaint =
                                                                        android.graphics.Paint()
                                                                                .apply {
                                                                                        color =
                                                                                                android.graphics
                                                                                                        .Color
                                                                                                        .WHITE
                                                                                        textAlign =
                                                                                                android.graphics
                                                                                                        .Paint
                                                                                                        .Align
                                                                                                        .CENTER
                                                                                        textSize =
                                                                                                24f
                                                                                }
                                                                drawText(
                                                                        "Time (seconds)",
                                                                        width / 2,
                                                                        height - 15f,
                                                                        textPaint
                                                                )
                                                        }

                                                        var currentTimeMarker =
                                                                firstTimestamp // Start from first
                                                        // timestamp
                                                        while (currentTimeMarker <= lastTimestamp) {
                                                                // Find closest point to this
                                                                // timestamp
                                                                val closestPointIndex =
                                                                        recentData.indexOfFirst {
                                                                                it.timestamp >=
                                                                                        currentTimeMarker
                                                                        }

                                                                if (closestPointIndex >= 0) {
                                                                        // Calculate screen position
                                                                        // based on point index
                                                                        val screenPosition =
                                                                                closestPointIndex
                                                                                        .toFloat() /
                                                                                        pointsPerScreenWidth
                                                                        val x =
                                                                                screenPosition *
                                                                                        screenWidth

                                                                        // Draw vertical time marker
                                                                        drawLine(
                                                                                color =
                                                                                        Color.White
                                                                                                .copy(
                                                                                                        alpha =
                                                                                                                .5f
                                                                                                ),
                                                                                start =
                                                                                        Offset(
                                                                                                x,
                                                                                                0f
                                                                                        ),
                                                                                end =
                                                                                        Offset(
                                                                                                x,
                                                                                                height
                                                                                        ),
                                                                                strokeWidth =
                                                                                        0.5.dp
                                                                                                .toPx()
                                                                        )

                                                                        // Draw time label
                                                                        drawContext.canvas
                                                                                .nativeCanvas
                                                                                .apply {
                                                                                        val textPaint =
                                                                                                android.graphics
                                                                                                        .Paint()
                                                                                                        .apply {
                                                                                                                color =
                                                                                                                        android.graphics
                                                                                                                                .Color
                                                                                                                                .WHITE
                                                                                                                textAlign =
                                                                                                                        android.graphics
                                                                                                                                .Paint
                                                                                                                                .Align
                                                                                                                                .CENTER
                                                                                                                textSize =
                                                                                                                        24f
                                                                                                        }
                                                                                        val seconds =
                                                                                                (currentTimeMarker -
                                                                                                        firstTimestamp) /
                                                                                                        1000
                                                                                        drawText(
                                                                                                "${seconds}s",
                                                                                                x,
                                                                                                height -
                                                                                                        10f,
                                                                                                textPaint
                                                                                        )
                                                                                }
                                                                }

                                                                currentTimeMarker +=
                                                                        secondMarkerIntervalMs
                                                        }

                                                        // Draw line segments connecting points
                                                        for (i in 0 until pointPositions.size - 1) {
                                                                val (current, currentX, currentY) =
                                                                        pointPositions[i]
                                                                val (next, nextX, nextY) =
                                                                        pointPositions[i + 1]

                                                                val phase =
                                                                        current.breathingPhase
                                                                                .lowercase()
                                                                                .trim()
                                                                val lineColor =
                                                                        when (phase) {
                                                                                "inhaling" ->
                                                                                        inhaleColor
                                                                                "exhaling" ->
                                                                                        exhaleColor
                                                                                "pause" ->
                                                                                        pauseColor
                                                                                else ->
                                                                                        Color.Gray // Use a distinct color for unknown phases
                                                                        }

                                                                // Use consistent line thickness for
                                                                // all phases
                                                                val lineThickness = 1.5.dp.toPx()

                                                                // Draw the line segment with the
                                                                // appropriate color
                                                                drawLine(
                                                                        color = lineColor,
                                                                        start =
                                                                                Offset(
                                                                                        currentX,
                                                                                        currentY
                                                                                ),
                                                                        end = Offset(nextX, nextY),
                                                                        strokeWidth = lineThickness,
                                                                        cap = StrokeCap.Round
                                                                )
                                                        }

                                                        // Draw a point for each data point with
                                                        // colored dots
                                                        pointPositions.forEach { (point, x, y) ->
                                                                val phase =
                                                                        point.breathingPhase
                                                                                .lowercase()
                                                                                .trim()
                                                                val dotColor =
                                                                        when (phase) {
                                                                                "inhaling" ->
                                                                                        inhaleColor
                                                                                "exhaling" ->
                                                                                        exhaleColor
                                                                                "pause" ->
                                                                                        pauseColor
                                                                                else -> Color.White
                                                                        }

                                                                // Draw a small colored dot
                                                                drawCircle(
                                                                        color = dotColor,
                                                                        radius =
                                                                                1.dp.toPx(), // Very
                                                                        // small dots
                                                                        center = Offset(x, y)
                                                                )
                                                        }
                                                }
                                        }
                                }
                        }
                }

                // Properly positioned legend below the chart with clear labels
                Box(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .background(
                                                Color.Black.copy(alpha = 0.9f)
                                        ) // Dark background for contrast
                                        .padding(
                                                top = 12.dp,
                                                bottom = 12.dp,
                                                start = 16.dp,
                                                end = 16.dp
                                        )
                ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                        "Breathing Phase Legend:",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                )

                                Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                        // Inhaling index - GREEN
                                        Box(
                                                modifier =
                                                        Modifier.height(1.5.dp)
                                                                .width(40.dp)
                                                                .background(inhaleColor)
                                        )
                                        Text(
                                                "Inhaling",
                                                color = Color.White,
                                                modifier =
                                                        Modifier.padding(start = 8.dp, end = 24.dp),
                                                fontSize = 16.sp
                                        )

                                        // Exhaling index - BLUE
                                        Box(
                                                modifier =
                                                        Modifier.height(1.5.dp)
                                                                .width(40.dp)
                                                                .background(exhaleColor)
                                        )
                                        Text(
                                                "Exhaling",
                                                color = Color.White,
                                                modifier =
                                                        Modifier.padding(start = 8.dp, end = 24.dp),
                                                fontSize = 16.sp
                                        )

                                        // Pause index - YELLOW
                                        Box(
                                                modifier =
                                                        Modifier.height(1.5.dp)
                                                                .width(40.dp)
                                                                .background(pauseColor)
                                        )
                                        Text(
                                                "Pause",
                                                color = Color.White,
                                                modifier = Modifier.padding(start = 8.dp),
                                                fontSize = 16.sp
                                        )

                                        Spacer(modifier = Modifier.weight(1f))

                                        // Scroll instruction
                                        if (respiratoryData.size > pointsIn4Seconds) {
                                                Text(
                                                        "Swipe to scroll",
                                                        color = Color.White.copy(alpha = 0.7f),
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Medium
                                                )
                                        }
                                }
                        }
                }
        }
}
