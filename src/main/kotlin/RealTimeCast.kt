package com.github.rei0925.mfweb

import com.github.rei0925.magufinance.api.FinanceAPI
import com.github.rei0925.magufinance.logger
import com.github.rei0925.magufinance.manager.News
import org.knowm.xchart.XYChart
import org.knowm.xchart.XYChartBuilder
import java.awt.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import fi.iki.elonen.NanoHTTPD
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Paths

class RealTimeCast {
    val api = FinanceAPI.getInstance()
    val companyManager = api.getCompanyManager()
    val historyManager = api.getHistoryManager()
    val newsManager = api.getNewsManager()
    private val logger = LoggerFactory.getLogger("MFWeb")
    private val tickerExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var chart3CompanyName: String = ""
    private var chart4CompanyName: String = ""
    private val chart1: XYChart
    private val chart2: XYChart
    private val chart3: XYChart
    private val chart4: XYChart
    private val charts: List<XYChart>
    private var timerFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private var companyCycleFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private val companyColors = mutableMapOf<String, Color>()
    private var tickerMode = "stocks" // "stocks"か"news"
    private var newsQueue: List<News> = emptyList()
    private var newsIndex = 0
    private val availableColors = listOf(
        Color.RED, Color.BLUE, Color.GREEN, Color.MAGENTA, Color.ORANGE, Color.CYAN,
        Color.PINK, Color.YELLOW, Color.GRAY, Color.DARK_GRAY
    )
    // MJPEG streaming server
    private val mjpegServer: MJPEGServer
    private val hls: HLS
    // Ticker state for rendering
    @Volatile private var tickerTextParts: List<Pair<String, Color>> = emptyList()
    @Volatile private var tickerText: String = ""
    @Volatile private var tickerColor: Color = Color.WHITE
    @Volatile private var tickerXPos: Int = 0
    @Volatile private var tickerTextWidth: Int = 0
    @Volatile private var tickerOnScrollEnd: (() -> Unit)? = null
    private val renderWidth = 1920
    private val renderHeight = 1080
    private val tickerHeight = 40
    private val chartWidth = renderWidth / 2
    private val chartHeight = (renderHeight - tickerHeight) / 2
    @Volatile private var lastRenderedFrame: BufferedImage? = null

    private val tickerFont: Font = loadMPlusFont()

    // --- 追加 ---
    private fun loadMPlusFont(): Font {
        val fontStream: InputStream = this::class.java.getResourceAsStream("/MPLUS.ttf")
            ?: throw IllegalStateException("MPLUS.ttf が見つかりません。resources直下に配置してください。")
        val font = Font.createFont(Font.TRUETYPE_FONT, fontStream)
        return font.deriveFont(Font.BOLD, 16f) // 必要に応じてサイズ変更
    }

    init {
        chart1 = XYChartBuilder().width(chartWidth).height(chartHeight).title("全社").build()
        chart2 = XYChartBuilder().width(chartWidth).height(chartHeight).title("国内平均").build()
        chart3 = XYChartBuilder().width(chartWidth).height(chartHeight).title(chart3CompanyName).build()
        chart4 = XYChartBuilder().width(chartWidth).height(chartHeight).title(chart4CompanyName).build()
        charts = listOf(chart1, chart2, chart3, chart4)
        tickerTextParts = emptyList()
        tickerText = ""
        tickerColor = Color.WHITE
        tickerXPos = renderWidth
        tickerTextWidth = 0
        mjpegServer = MJPEGServer(8080) { getLatestFrame() }
        val hlsOutputDir = Paths.get("stream/hls") // 任意のパス
        hls = HLS(hlsOutputDir)
    }

    fun start() {
        if (timerFuture == null) {
            timerFuture = tickerExecutor.scheduleAtFixedRate({
                updateData()
            }, 0, 5, TimeUnit.SECONDS)
        }
        if (companyCycleFuture == null) {
            val companies = companyManager.companyList.map { it.name }
            val companyPairs = companies.chunked(2)
            var currentPairIndex = 0
            companyCycleFuture = tickerExecutor.scheduleAtFixedRate({
                val pair = companyPairs.getOrNull(currentPairIndex) ?: emptyList()
                chart3.seriesMap.clear()
                chart4.seriesMap.clear()
                if (pair.isNotEmpty()) {
                    updateSingleCompany(chart3, pair[0])
                }
                if (pair.size > 1) {
                    updateSingleCompany(chart4, pair[1])
                }
                chart3CompanyName = if (pair.isNotEmpty()) pair[0] else ""
                chart4CompanyName = if (pair.size > 1) pair[1] else ""
                currentPairIndex = (currentPairIndex + 1) % companyPairs.size
            }, 0, 10, TimeUnit.SECONDS)
        }
        startTicker()
        startHLSStreaming()
        mjpegServer.start()
        logger.info("ServerStart")
        // Start frame rendering loop
        tickerExecutor.scheduleAtFixedRate({
            renderFrame()
        }, 0, 150, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        timerFuture?.cancel(true)
        companyCycleFuture?.cancel(true)
        stopTicker()
        stopHLSStreaming()
        timerFuture = null
        companyCycleFuture = null
        mjpegServer.stop()
        tickerExecutor.shutdownNow()
    }

    fun startHLSStreaming() {
        // HLS 配信開始
        hls.start()

        // 定期的にフレームを書き込むループ
        tickerExecutor.scheduleAtFixedRate({
            val frame = getLatestFrame()
            hls.writeFrame(frame)
        }, 0, 16, TimeUnit.MILLISECONDS) // 60FPS相当
    }

    // 停止する場合
    fun stopHLSStreaming() {
        hls.stop()
    }

    private fun updateData() {
        // chart1: 全社
        val companies = companyManager.companyList
        // Prepare data for all companies and for average
        val chart1SeriesData = mutableListOf<Triple<String, List<Date>, List<Number>>>()
        val chart1Colors = mutableMapOf<String, Color>()
        val allCompanyNames = (companies.map { it.name } + listOf(chart3CompanyName, chart4CompanyName)).distinct()
        allCompanyNames.forEach { name ->
            val entries = historyManager.getStockHistory(name).takeLast(50)
            if (entries.isNotEmpty()) {
                val xData = entries.map { Date(it.timestamp) }
                val yData = entries.map { it.stockPrice }
                chart1SeriesData.add(Triple(name, xData, yData))
                val idx = allCompanyNames.indexOf(name)
                val color = availableColors[idx % availableColors.size]
                chart1Colors[name] = color
            }
        }
        // Average series for chart1 and chart2
        val avgEntries = historyManager.getAverageHistory().takeLast(50)
        val avgXData = avgEntries.map { Date(it.timestamp) }
        val avgYData = avgEntries.map { it.averagePrice }
        val avgColor = Color.MAGENTA
        // chart2: 国内平均
        val chart2SeriesData = if (avgEntries.isNotEmpty()) Triple("国内平均", avgXData, avgYData) else null

        // Find min/max for y-axis scaling for chart1 and chart2
        val chart1YMin = if (avgYData.isNotEmpty()) avgYData.minOrNull() else null
        val chart1YMax = if (avgYData.isNotEmpty()) avgYData.maxOrNull() else null

        chart1.seriesMap.clear()
        companyColors.clear()
        chart1SeriesData.forEach { (name, xData, yData) ->
            val series = chart1.addSeries(name, xData, yData)
            val color = chart1Colors[name] ?: availableColors[0]
            companyColors[name] = color
            series.lineColor = color
            series.markerColor = color
        }
        // Add average series to chart1
        if (avgXData.isNotEmpty() && avgYData.isNotEmpty()) {
            val avgSeries = chart1.addSeries("国内平均", avgXData, avgYData)
            avgSeries.lineColor = avgColor
            avgSeries.markerColor = avgColor
            companyColors["国内平均"] = avgColor
            if (chart1YMin != null && chart1YMax != null) {
                chart1.styler.yAxisMin = chart1YMin * 0.98
                chart1.styler.yAxisMax = chart1YMax * 1.02
            }
        }
        // chart2: 国内平均
        chart2.seriesMap.clear()
        if (chart2SeriesData != null) {
            val (name, xData, yData) = chart2SeriesData
            val avgSeries = chart2.addSeries(name, xData, yData)
            avgSeries.lineColor = avgColor
            avgSeries.markerColor = avgColor
            companyColors["国内平均"] = avgColor
            if (chart1YMin != null && chart1YMax != null) {
                chart2.styler.yAxisMin = chart1YMin * 0.98
                chart2.styler.yAxisMax = chart1YMax * 1.02
            }
        }
        if (!companyColors.containsKey("国内平均")) {
            companyColors["国内平均"] = avgColor
        }
        adjustYAxis(chart1)
        adjustYAxis(chart2)
    }

    private fun updateSingleCompany(chart: XYChart, companyName: String) {
        val entries = historyManager.getStockHistory(companyName).takeLast(50)
        val xData = entries.map { Date(it.timestamp) }
        val yData = entries.map { it.stockPrice }
        val color = companyColors[companyName] ?: availableColors[0]
        chart.seriesMap.clear()
        chart.title = companyName
        if (entries.isNotEmpty()) {
            val series = chart.addSeries(companyName, xData, yData)
            series.lineColor = color
            series.markerColor = color
        }
        adjustYAxis(chart)
    }

    private fun adjustYAxis(chart: XYChart) {
        val allY = chart.seriesMap.values.flatMap { series -> series.yData.map { it } }
        if (allY.isNotEmpty()) {
            val minY = allY.minOrNull()!! * 0.95
            val maxY = allY.maxOrNull()!! * 1.05
            chart.styler.yAxisMin = kotlin.math.ceil(minY)
            chart.styler.yAxisMax = kotlin.math.ceil(maxY)
        }
    }

    private fun startTicker() {
        val x = renderWidth
        val snapshotPrices = companyManager.companyList.associate { it.name to it.stockPrice.toInt() }.toMutableMap()
        val snapshotAvg = historyManager.getAverageHistory().lastOrNull()?.averagePrice?.toInt() ?: 0
        tickerMode = "stocks"
        val textParts = buildStockText(snapshotPrices, snapshotAvg)
        setTickerTextParts(textParts)
        tickerXPos = x
        tickerOnScrollEnd = {
            when (tickerMode) {
                "stocks" -> {
                    val newTextParts = buildStockText(snapshotPrices, snapshotAvg)
                    val newNewsQueue = newsManager.getAllNews()
                    setTickerTextParts(newTextParts)
                    tickerXPos = renderWidth
                    newsQueue = newNewsQueue
                    newsIndex = 0
                    tickerMode = if (newsQueue.isNotEmpty()) "news" else "stocks"
                }
                "news" -> {
                    if (newsQueue.isNotEmpty()) {
                        val concatenatedNews = newsQueue.joinToString(" \u00A0\u00A0\u00A0\u00A0\u00A0 ") { news -> buildNewsText(news) }
                        setTickerText(concatenatedNews, Color.WHITE)
                        tickerXPos = renderWidth
                        tickerMode = "stocks"
                    } else {
                        setTickerText("", Color.WHITE)
                        tickerXPos = renderWidth
                        tickerMode = "stocks"
                    }
                }
            }
        }
        // Schedule ticker scroll update
        tickerExecutor.scheduleAtFixedRate({
            updateTickerScroll()
        }, 0, 25, TimeUnit.MILLISECONDS)
    }

    private fun stopTicker() {
        tickerOnScrollEnd = null
    }

    private fun buildStockText(snapshotPrices: Map<String, Int>, snapshotAvg: Int): List<Pair<String, Color>> {
        val avg = historyManager.getAverageHistory().lastOrNull()?.averagePrice?.toInt() ?: snapshotAvg
        val avgDiff = avg - snapshotAvg
        val avgColor = when {
            avgDiff > 0 -> Color.RED
            avgDiff < 0 -> Color.GREEN
            else -> Color.GRAY
        }
        val avgArrow = when {
            avgDiff > 0 -> "+$avgDiff"
            avgDiff < 0 -> "-${-avgDiff}"
            else -> "0"
        }
        val parts = mutableListOf<Pair<String, Color>>()
        parts.add("国内平均 $avg 円 " to Color.WHITE)
        parts.add("$avgArrow " to avgColor)
        parts.add("｜" to Color.WHITE)

        companyManager.companyList.forEachIndexed { index, company ->
            val oldPrice = snapshotPrices[company.name] ?: company.stockPrice.toInt()
            val diff = company.stockPrice.toInt() - oldPrice
            val diffColor = when {
                diff > 0 -> Color.RED
                diff < 0 -> Color.GREEN
                else -> Color.GRAY
            }
            val mainText = " ${company.name} ${company.stockPrice.toInt()}円 "
            val diffText = if (diff != 0) (if (diff > 0) "+${kotlin.math.abs(diff)}" else "-${kotlin.math.abs(diff)}") else "0"
            parts.add(mainText to Color.WHITE)
            parts.add(diffText to diffColor)
            if (index < companyManager.companyList.size - 1) {
                parts.add("｜" to Color.WHITE)
            }
        }
        return parts
    }
    private fun buildNewsText(news: News): String {
        return "   【${news.genre}】 ${news.content}"
    }

    // --- MJPEG rendering and ticker logic ---
    private fun setTickerTextParts(parts: List<Pair<String, Color>>) {
        tickerTextParts = parts
        tickerText = ""
        tickerTextWidth = calcTickerTextWidth(parts)
    }
    private fun setTickerText(text: String, color: Color = Color.WHITE) {
        tickerTextParts = listOf(text to color)
        tickerText = text
        tickerColor = color
        tickerTextWidth = calcTickerTextWidth(tickerTextParts)
    }
    private fun calcTickerTextWidth(parts: List<Pair<String, Color>>): Int {
        val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.font = tickerFont
        val fm = g.fontMetrics
        val width = parts.sumOf { fm.stringWidth(it.first) } + (parts.size - 1) * fm.stringWidth(" ")
        g.dispose()
        return width
    }
    private fun updateTickerScroll() {
        // Move ticker text leftwards, and reset if out of view
        tickerXPos -= 3
        if (tickerTextWidth == 0) tickerTextWidth = calcTickerTextWidth(tickerTextParts)
        if (tickerXPos + tickerTextWidth < 0) {
            tickerOnScrollEnd?.invoke()
            tickerXPos = renderWidth
        }
    }
    private fun renderFrame() {
        val img = BufferedImage(renderWidth, renderHeight, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.BLACK
        g.fillRect(0, 0, renderWidth, renderHeight)

        // --- 既存のチャート描画 ---
        val chartImgs = charts.map {
            val chartImg = BufferedImage(chartWidth, chartHeight, BufferedImage.TYPE_INT_RGB)
            val cg = chartImg.createGraphics()
            cg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            it.paint(cg, chartWidth, chartHeight)
            cg.dispose()
            chartImg
        }
        g.drawImage(chartImgs[0], 0, 0, null)
        g.drawImage(chartImgs[1], chartWidth, 0, null)
        g.drawImage(chartImgs[2], 0, chartHeight, null)
        g.drawImage(chartImgs[3], chartWidth, chartHeight, null)

        // --- ティッカーレイヤー描画順序 ---
        // 1. ティッカー背景の黒い四角
        val tickerY = renderHeight - tickerHeight
        g.color = Color.BLACK
        g.fillRect(0, tickerY, renderWidth, tickerHeight)

        // 2. 時計描画部分
        g.font = tickerFont
        val fm = g.fontMetrics
        val now = java.time.LocalTime.now()
        val timeStr = now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        val timeWidth = fm.stringWidth(timeStr)

        val y = tickerY + (tickerHeight + fm.ascent - fm.descent) / 2


        // 3. ニューステキストやティッカーをその上に描画
        var currentX = tickerXPos
        for ((part, color) in tickerTextParts) {
            g.color = color
            val width = fm.stringWidth(part)
            g.drawString(part, currentX, y)
            currentX += width
            if (part != tickerTextParts.lastOrNull()?.first) {
                currentX += fm.stringWidth(" ")
            }
        }

        // 時計の位置と背景矩形の高さを計算
        val timeX = renderWidth - timeWidth - 10 // 右端から10px余白
        val timeFontHeight = fm.height
        val timeBgHeight = timeFontHeight + 4 // フォントサイズ+4px程度
        val timeBgY = tickerY + (tickerHeight - timeBgHeight) / 2
        val timeBgX = timeX - 8 // 時計の左に8px余裕
        val timeBgWidth = timeWidth + 16 // 左右8pxずつ余裕
        // --- 時計の下に黒い半透明背景矩形を描画 ---
        g.color = Color(0, 0, 0)
        g.fillRoundRect(timeBgX, timeBgY, timeBgWidth, timeBgHeight, 10, 10)
        // 時計文字列描画
        g.color = Color.LIGHT_GRAY
        g.drawString(timeStr, timeX, y)

        g.dispose()
        lastRenderedFrame = img
    }
    fun getLatestFrame(): BufferedImage {
        return lastRenderedFrame ?: run {
            val img = BufferedImage(renderWidth, renderHeight, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color.BLACK
            g.fillRect(0, 0, renderWidth, renderHeight)
            g.dispose()
            img
        }
    }

    companion object {
        @JvmStatic
        var instance: RealTimeCast? = null

        fun start() {
            logger.info("O-Start")
            if (instance == null) {
                logger.info("IF")
                instance = RealTimeCast()
                instance?.start()
            }
        }

        fun stopInstance() {
            instance?.stop()
            instance = null
        }
    }
}

// --- MJPEGServer using NanoHTTPD ---
class MJPEGServer(
    port: Int,
    private val getFrame: () -> BufferedImage
) : NanoHTTPD(port) {
    @Volatile
    private var running = false

    override fun start() {
        running = true
        super.start(SOCKET_READ_TIMEOUT, false)
        println("MJPEG streaming on http://localhost:$listeningPort/")
    }

    override fun stop() {
        running = false
        super.stop()
    }

    override fun serve(session: IHTTPSession): Response {
        // --- HLS配信ルート追加 ---
        // /stream/hls: HLS配信ページ
        if (session.uri == "/stream/hls") {
            return newFixedLengthResponse(
                Response.Status.OK,
                "text/html",
                """
<html>
  <head>
    <meta charset="UTF-8">
    <style>
      body {
        background: #181a1b;
        color: #e5e5e5;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        min-height: 100vh;
        margin: 0;
        font-family: 'Segoe UI', 'Meiryo', sans-serif;
      }
      h1 {
        text-align: center;
        margin-top: 2rem;
        margin-bottom: 1.2rem;
        font-weight: 600;
        letter-spacing: 0.03em;
      }
      .center-div {
        display: flex;
        justify-content: center;
        align-items: center;
        margin: 1.2rem 0;
        gap: 1.2em;
      }
      .video-controls-bar {
        display: flex;
        flex-direction: row;
        justify-content: center;
        align-items: center;
        gap: 1.2em;
        margin-bottom: 1.2em;
      }
      button {
        background: #282c34;
        color: #e5e5e5;
        border: none;
        padding: 0.8em 2.1em;
        border-radius: 0.5em;
        font-size: 1.12em;
        font-weight: 500;
        cursor: pointer;
        box-shadow: 0 2px 8px rgba(0,0,0,0.18);
        transition: background 0.18s, color 0.18s, transform 0.09s;
        margin: 0 0.3em;
        font-family: 'Segoe UI', 'Meiryo', sans-serif;
        letter-spacing: 0.01em;
        display: flex;
        align-items: center;
        gap: 0.5em;
      }
      button:hover, button:focus {
        background: #3d4147;
        color: #fff;
        transform: translateY(-2px) scale(1.04);
      }
      .icon {
        font-size: 1.19em;
        line-height: 1;
        display: inline-block;
        vertical-align: middle;
      }
      video {
        display: block;
        margin: 2rem auto 1rem auto;
        border-radius: 0.7em;
        box-shadow: 0 2px 24px 0 #111a  ;
        outline: none;
        background: #222;
      }
      pre {
        background: #222;
        color: #b9e0ff;
        border-radius: 0.5em;
        padding: 1em;
        margin: 2em auto 1em auto;
        max-width: 90vw;
        text-align: center;
        font-size: 0.98em;
        box-shadow: 0 1px 10px #0005;
      }
      .annotation {
        color: #b0b0b0;
        font-size: 0.98em;
        text-align: center;
        margin: 0.5em auto 1.5em auto;
        max-width: 700px;
      }
      a {
        text-align: center;
        display: inline-block;
        text-decoration: none;
      }
    </style>
  </head>
  <body>
    <h1>高画質モード（HLS）</h1>
    <div class="center-div">
      <a href="/"><button>トップに戻る</button></a>
    </div>
    <!--
      以下のvideoタグは、HLS映像を一時停止・シーク・リロードできないよう制限しています。
      - controls属性を削除（コントロールバー非表示）
      - disablePictureInPicture属性でピクチャインピクチャを禁止
      - oncontextmenu="return false" で右クリック禁止
      - onkeydown, onmousedown でキーボード・マウスによる操作を補助的に無効化
    -->
    <video
      id="hlsVideo"
      width="960"
      height="540"
      autoplay
      muted
      playsinline
      disablePictureInPicture
      oncontextmenu="return false"
      onkeydown="return false"
      onmousedown="return false"
      tabindex="0"
    >
      <source src="/stream/hls/index.m3u8" type="application/vnd.apple.mpegurl">
      お使いのブラウザはHLS再生に対応していません。
    </video>
    <!-- ▼ 映像操作ボタン: フルスクリーン・リロード -->
    <div class="video-controls-bar">
      <!-- フルスクリーンボタン: 映像を全画面表示 -->
      <button id="fullscreenBtn" title="全画面表示">
        <span class="icon">&#x26F6;</span>
        フルスクリーン
      </button>
      <!-- リロードボタン: 映像を再読み込み -->
      <button id="reloadBtn" title="映像リロード">
        <span class="icon">&#x21BB;</span>
        リロード
      </button>
    </div>
    <div class="annotation">
      HLS再生ができない場合、Google Chrome系はhls.jsで再生されます。<br>
      SafariやEdgeはネイティブ再生対応です。<br>
      <br>
      MaguFinanceRealTimeChart
    </div>
    <script>
      // hls.jsを使う場合の例
      if (!document.createElement('video').canPlayType('application/vnd.apple.mpegurl')) {
        var script = document.createElement('script');
        script.src = 'https://cdn.jsdelivr.net/npm/hls.js@latest';
        script.onload = function() {
          var video = document.getElementById('hlsVideo');
          if (Hls.isSupported()) {
            var hls = new Hls();
            hls.loadSource('/stream/hls/index.m3u8');
            hls.attachMedia(video);
            window._hlsjs = hls; // for reload
          }
        };
        document.head.appendChild(script);
      }
      // 再生・一時停止・シーク・リロードなどを更に防ぐためのイベント
      (function() {
        var video = document.getElementById('hlsVideo');
        // 一時停止を防止
        video.addEventListener('pause', function(e) {
          if (video.paused) video.play();
        });
        // シーク禁止
        video.addEventListener('seeking', function(e) {
          video.currentTime = video.startTime || 0;
        });
        // リロード防止
        video.addEventListener('ended', function(e) {
          video.currentTime = 0;
          video.play();
        });
        // キーボードによる制御無効化
        video.addEventListener('keydown', function(e) {
          e.preventDefault();
          return false;
        });
        // マウスによる制御無効化
        video.addEventListener('mousedown', function(e) {
          e.preventDefault();
          return false;
        });
      })();

      // ▼ フルスクリーン・リロードボタンのイベント
      // フルスクリーンボタン: videoを全画面表示する
      // リロードボタン: videoを再読み込みする
      document.addEventListener('DOMContentLoaded', function() {
        var video = document.getElementById('hlsVideo');
        var fullscreenBtn = document.getElementById('fullscreenBtn');
        var reloadBtn = document.getElementById('reloadBtn');
        // --- フルスクリーンボタン ---
        // 用途: 映像を全画面表示
        fullscreenBtn.addEventListener('click', function() {
          if (video.requestFullscreen) {
            video.requestFullscreen();
          } else if (video.webkitRequestFullscreen) {
            video.webkitRequestFullscreen();
          } else if (video.msRequestFullscreen) {
            video.msRequestFullscreen();
          }
        });
        // --- リロードボタン ---
        // 用途: 映像を再読み込み
        reloadBtn.addEventListener('click', function() {
          // HLS.js使用時はdetach+再load
          if (window._hlsjs) {
            window._hlsjs.detachMedia();
            window._hlsjs.loadSource('/stream/hls/index.m3u8');
            window._hlsjs.attachMedia(video);
          } else {
            // ネイティブ再生の場合はsrc再設定
            var src = video.querySelector('source');
            var origSrc = src ? src.getAttribute('src') : '';
            video.pause();
            // 強制再読込
            src.setAttribute('src', origSrc + (origSrc.indexOf('?')>-1 ? '&' : '?') + 't=' + Date.now());
            video.load();
            video.play();
          }
        });
      });
    </script>
    <pre>
      <!--
      HLSセグメント生成例（FFmpeg）:
      ffmpeg -re -f image2 -pattern_type glob -i 'frame_%05d.jpg' \
        -c:v libx264 -preset ultrafast -tune zerolatency -f hls \
        -hls_time 2 -hls_list_size 5 -hls_flags delete_segments \
        ./stream/hls/index.m3u8
      -->
    </pre>
  </body>
</html>
                """.trimIndent()
            )
        }
        // /stream/hls/index.m3u8: FFmpeg により生成されたマニフェストを返却
        if (session.uri == "/stream/hls/index.m3u8") {
            // FFmpeg により生成された index.m3u8 を返す
            val hlsManifestPath = Paths.get("stream/hls/index.m3u8").toFile()
            if (hlsManifestPath.exists()) {
                // FFmpeg による H.264 + HLS マニフェストを返却
                val manifestContent = hlsManifestPath.readText()
                return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", manifestContent)
            } else {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "HLSマニフェストが存在しません")
            }
        }
        // /stream/hls/segmentN.ts: FFmpeg により生成された .ts セグメントを返却
        if (session.uri.startsWith("/stream/hls/") && session.uri.endsWith(".ts")) {
            // FFmpeg により生成された .ts セグメントを返す
            // 例: /stream/hls/segment0.ts
            val segName = session.uri.substringAfterLast("/")
            val segFile = Paths.get("stream/hls", segName).toFile()
            if (segFile.exists()) {
                // FFmpeg による H.264 エンコード済みセグメントを返却
                val fis = segFile.inputStream()
                val resp = newFixedLengthResponse(Response.Status.OK, "video/MP2T", fis, segFile.length())
                resp.addHeader("Access-Control-Allow-Origin", "*")
                return resp
            } else {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "HLSセグメントが存在しません")
            }
        }

        // --- 既存トップページ ---
        if (session.uri == "/") {
            return newFixedLengthResponse(
                Response.Status.OK,
                "text/html",
                """
<html>
  <head>
    <meta charset="UTF-8">
    <style>
      body {
        background: #181a1b;
        color: #e5e5e5;
        min-height: 100vh;
        margin: 0;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        font-family: 'Segoe UI', 'Meiryo', sans-serif;
      }
      h1 {
        text-align: center;
        margin-top: 2.5rem;
        margin-bottom: 1.5rem;
        font-weight: 600;
        letter-spacing: 0.03em;
      }
      .center-div {
        display: flex;
        justify-content: center;
        align-items: center;
        margin: 2rem 0 0.5rem 0;
        gap: 1.2em;
      }
      button {
        background: #282c34;
        color: #e5e5e5;
        border: none;
        padding: 0.8em 2.1em;
        border-radius: 0.5em;
        font-size: 1.12em;
        font-weight: 500;
        cursor: pointer;
        box-shadow: 0 2px 8px rgba(0,0,0,0.18);
        transition: background 0.18s, color 0.18s, transform 0.09s;
        margin: 0 0.3em;
        font-family: 'Segoe UI', 'Meiryo', sans-serif;
        letter-spacing: 0.01em;
      }
      button:hover, button:focus {
        background: #3d4147;
        color: #fff;
        transform: translateY(-2px) scale(1.04);
      }
      a {
        text-align: center;
        display: inline-block;
        text-decoration: none;
      }
    </style>
  </head>
  <body>
    <h1>リアルタイム株価ストリーム</h1>
    <!-- 遷移ボタン -->
    <div class="center-div">
      <a href="/stream/mjpeg"><button>低遅延モード（MJPEG）</button></a>
      <a href="/stream/hls"><button>高画質モード（HLS）</button></a>
    </div>
  </body>
</html>
                """.trimIndent()
            )
        }

        // --- MJPEG配信ページ（HTMLラッパー） ---
        if (session.uri == "/stream/mjpeg") {
            // MJPEGストリームのHTMLページ（ダークテーマ・中央揃え・ボタン・imgでストリーム）
            return newFixedLengthResponse(
                Response.Status.OK,
                "text/html",
                """
<html>
  <head>
    <meta charset="UTF-8">
    <style>
      body {
        background: #181a1b;
        color: #e5e5e5;
        min-height: 100vh;
        margin: 0;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        font-family: 'Segoe UI', 'Meiryo', sans-serif;
      }
      h1 {
        text-align: center;
        margin-top: 2.5rem;
        margin-bottom: 1.5rem;
        font-weight: 600;
        letter-spacing: 0.03em;
      }
      .center-div {
        display: flex;
        justify-content: center;
        align-items: center;
        margin: 2rem 0 0.5rem 0;
        gap: 1.2em;
      }
      button {
        background: #282c34;
        color: #e5e5e5;
        border: none;
        padding: 0.8em 2.1em;
        border-radius: 0.5em;
        font-size: 1.12em;
        font-weight: 500;
        cursor: pointer;
        box-shadow: 0 2px 8px rgba(0,0,0,0.18);
        transition: background 0.18s, color 0.18s, transform 0.09s;
        margin: 0 0.3em;
        font-family: 'Segoe UI', 'Meiryo', sans-serif;
        letter-spacing: 0.01em;
        display: flex;
        align-items: center;
        gap: 0.5em;
      }
      button:hover, button:focus {
        background: #3d4147;
        color: #fff;
        transform: translateY(-2px) scale(1.04);
      }
      .icon {
        font-size: 1.19em;
        line-height: 1;
        display: inline-block;
        vertical-align: middle;
      }
      .mjpeg-box {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        margin-top: 1.5em;
        margin-bottom: 1.2em;
      }
      .mjpeg-placeholder {
        background: #222;
        border-radius: 0.7em;
        box-shadow: 0 2px 24px 0 #111a;
        outline: none;
        display: flex;
        align-items: center;
        justify-content: center;
        overflow: hidden;
        position: relative;
      }
      .annotation {
        color: #b0b0b0;
        font-size: 0.98em;
        text-align: center;
        margin: 0.5em auto 1.5em auto;
        max-width: 700px;
      }
      a {
        text-align: center;
        display: inline-block;
        text-decoration: none;
      }
      img#mjpegStream {
        display: block;
        max-width: 100%;
        border-radius: 0.7em;
        box-shadow: 0 2px 24px 0 #111a;
        background: #222;
        width: 960px;
        height: 540px;
        object-fit: contain;
        outline: none;
        margin: 0 auto;
      }
      .video-controls-bar {
        display: flex;
        flex-direction: row;
        justify-content: center;
        align-items: center;
        gap: 1.2em;
        margin-top: 1em;
      }
      @media (max-width: 1100px) {
        img#mjpegStream {
          width: 98vw;
          height: auto;
          max-height: 60vw;
        }
      }
    </style>
  </head>
  <body>
    <h1>低遅延モード（MJPEG）</h1>
    <div class="center-div">
      <a href="/"><button>トップに戻る</button></a>
    </div>
    <div class="mjpeg-box">
      <!--
        MJPEGはvideoタグではなくimgタグでストリーム再生します。
        <img> の src に /mjpeg を指定します。
      -->
      <div class="mjpeg-placeholder">
        <img id="mjpegStream" src="/mjpeg" alt="MJPEGストリーム" draggable="false" />
      </div>
      <div class="video-controls-bar">
        <button id="fullscreenBtn" title="全画面表示">
          <span class="icon">&#x26F6;</span>
          フルスクリーン
        </button>
        <button id="reloadBtn" title="映像リロード">
          <span class="icon">&#x21BB;</span>
          リロード
        </button>
      </div>
    </div>
    <div class="annotation">
      MJPEG方式は低遅延ですが、画質はHLSより劣ります。<br>
      <b>映像が止まった場合はリロードボタンをお試しください。</b><br>
      <br>
      MaguFinanceRealTimeChart
    </div>
    <script>
      // フルスクリーンボタン: img要素を全画面表示
      document.addEventListener('DOMContentLoaded', function() {
        var img = document.getElementById('mjpegStream');
        var fullscreenBtn = document.getElementById('fullscreenBtn');
        var reloadBtn = document.getElementById('reloadBtn');
        fullscreenBtn.addEventListener('click', function() {
          if (img.requestFullscreen) {
            img.requestFullscreen();
          } else if (img.webkitRequestFullscreen) {
            img.webkitRequestFullscreen();
          } else if (img.msRequestFullscreen) {
            img.msRequestFullscreen();
          }
        });
        reloadBtn.addEventListener('click', function() {
          // srcを強制リロード
          var origSrc = img.getAttribute('src').split('?')[0];
          img.setAttribute('src', origSrc + '?t=' + Date.now());
        });
      });
    </script>
  </body>
</html>
                """.trimIndent()
            )
        }
        // --- MJPEGストリーム本体 ---
        if (session.uri == "/mjpeg") {
            val boundary = "mjpegstream"
            val pipedIn = PipedInputStream()
            val pipedOut = PipedOutputStream(pipedIn)

            val response = newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=--$boundary",
                pipedIn
            )
            response.addHeader("Connection", "close")
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("Pragma", "no-cache")

            Thread {
                try {
                    while (running) {
                        val img = getFrame()
                        val baos = ByteArrayOutputStream()

                        // 高画質 JPEG
                        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
                        val ios = ImageIO.createImageOutputStream(baos)
                        writer.output = ios
                        val param = writer.defaultWriteParam
                        if (param.canWriteCompressed()) {
                            param.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
                            param.compressionQuality = 0.9f
                        }
                        writer.write(null, javax.imageio.IIOImage(img, null, null), param)
                        writer.dispose()
                        ios.close()

                        val jpeg = baos.toByteArray()
                        val partHeader = (
                                "\r\n--$boundary\r\n" +
                                        "Content-Type: image/jpeg\r\n" +
                                        "Content-Length: ${jpeg.size}\r\n\r\n"
                                ).toByteArray()

                        pipedOut.write(partHeader)
                        pipedOut.write(jpeg)
                        pipedOut.flush()

                        Thread.sleep(33) // 約30FPS
                    }
                } catch (_: Exception) {
                } finally {
                    try { pipedOut.close() } catch (_: Exception) {}
                }
            }.start()

            return response
        }

        // その他はデフォルト
        return super.serve(session)
    }
}