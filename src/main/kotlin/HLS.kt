package com.github.rei0925.mfweb

import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue

class HLS(private val outputDir: Path) {

    private var ffmpegProcess: Process? = null
    private var ffmpegInput: BufferedOutputStream? = null

    // フレームキュー（容量100）
    private val frameQueue: BlockingQueue<BufferedImage> = ArrayBlockingQueue(100)
    private var writerThread: Thread? = null
    @Volatile
    private var running = false

    /**
     * FFmpeg を外部プロセスで起動し、BufferedImage を非同期キュー経由でパイプ入力して
     * HLS 1080p60FPS 配信用の .ts セグメントと index.m3u8 を生成します。
     *
     * 変更点:
     * - FFmpeg コマンドに '-vsync 0' と '-fflags +nobuffer' を追加し、60FPS の安定化をサポート
     * - BufferedImage の送信を非同期キュー化し、writer thread で FFmpeg に書き込む方式に変更
     * - writeFrame() はキューに追加するだけに変更し、内部で BGRA変換して FFmpeg に送る
     * - stop() で writer thread と FFmpeg プロセスを安全に終了
     * - HLSセグメント時間を短くして再生安定化（-hls_time を 1 秒に変更）
     *
     * 使い方:
     * 1. start() を呼び、FFmpeg プロセスを起動します。
     * 2. writeFrame(image) を呼び、フレームをキューに追加します（非同期で FFmpeg に送信されます）。
     * 3. stop() を呼び、writer thread と FFmpeg プロセスを安全に終了します。
     */
    fun start() {
        if (ffmpegProcess != null) {
            throw IllegalStateException("FFmpeg process is already running")
        }

        // 出力ディレクトリが存在しない場合は作成
        val dirFile = outputDir.toFile()
        if (!dirFile.exists()) {
            dirFile.mkdirs()
        }

        val logFile = outputDir.resolve("ffmpeg.log").toFile()

        val command = listOf(
            "ffmpeg",
            "-y",
            "-f", "rawvideo",
            "-pix_fmt", "bgra",
            "-s", "1920x1080",
            "-r", "60",
            "-i", "-", // 標準入力
            "-vsync", "0",       // 追加: フレーム同期を無効化して60FPS安定化
            "-fflags", "+nobuffer", // 追加: バッファリングを無効化して遅延軽減
            "-c:v", "h264_nvenc", // GPU エンコード
            "-preset", "p1",      // NVENC 用プリセット（p1～p7, p1が高品質）
            "-b:v", "6000k",      // ビットレート
            "-maxrate", "8000k",
            "-bufsize", "12000k",
            "-g", "120",
            "-sc_threshold", "0",
            "-f", "hls",
            "-hls_time", "1",     // HLSセグメント時間を短くして再生安定化
            "-hls_list_size", "5",
            "-hls_flags", "delete_segments",
            "-hls_segment_filename", outputDir.resolve("segment_%03d.ts").toString(),
            outputDir.resolve("index.m3u8").toString()
        )

        try {
            val pb = ProcessBuilder(command)
            // 標準出力・標準エラーをログファイルにリダイレクト
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile))
            ffmpegProcess = pb.start()
            ffmpegInput = BufferedOutputStream(ffmpegProcess!!.outputStream)

            running = true
            writerThread = Thread {
                try {
                    val out = ffmpegInput ?: return@Thread
                    while (running || frameQueue.isNotEmpty()) {
                        val image = frameQueue.poll()
                        if (image != null) {
                            // BGRAフォーマットで書き込む
                            val width = image.width
                            val height = image.height
                            val pixels = IntArray(width * height)
                            image.getRGB(0, 0, width, height, pixels, 0, width)

                            // BGRA でバイト配列に変換
                            val buffer = ByteArray(width * height * 4)
                            for (i in pixels.indices) {
                                val argb = pixels[i]
                                val base = i * 4
                                buffer[base] = (argb and 0xFF).toByte() // B
                                buffer[base + 1] = (argb shr 8 and 0xFF).toByte() // G
                                buffer[base + 2] = (argb shr 16 and 0xFF).toByte() // R
                                buffer[base + 3] = (argb shr 24 and 0xFF).toByte() // A
                            }

                            try {
                                out.write(buffer)
                                out.flush()
                            } catch (e: IOException) {
                                // 書き込み失敗時はループを抜ける
                                break
                            }
                        } else {
                            // キューが空なら少し待つ
                            Thread.sleep(5)
                        }
                    }
                } catch (e: InterruptedException) {
                    // スレッド割り込み時は終了
                } catch (e: Exception) {
                    // その他例外は無視して終了
                }
            }
            writerThread?.start()

            // ログ表示用ウィンドウを別スレッドで起動
            // ここでは簡単にログファイルをリアルタイムに表示する別プロセスとして 'tail -f' を起動する例を示します
            // Windowsの場合は適宜別の方法に置き換えてください
            Thread {
                try {
                    val logViewerCommand = listOf("tail", "-f", logFile.absolutePath)
                    val logViewerPb = ProcessBuilder(logViewerCommand)
                    logViewerPb.inheritIO()
                    val logViewerProcess = logViewerPb.start()
                    logViewerProcess.waitFor()
                } catch (ex: Exception) {
                    // ログ表示用ウィンドウの起動に失敗してもメイン処理には影響しない
                }
            }.start()

        } catch (e: IOException) {
            throw RuntimeException("Failed to start FFmpeg process", e)
        }
    }

    /**
     * BufferedImage を FFmpeg に送信するためのキューに追加します。
     * start() を呼んだ後に使用してください。
     * 送信は非同期で行われ、writeFrame() は高速に戻ります。
     */
    @Synchronized
    fun writeFrame(image: BufferedImage) {
        if (!running) {
            throw IllegalStateException("FFmpeg process is not running")
        }
        // キューに追加。キューが満杯の場合は破棄してフレームを落とす（負荷軽減）
        frameQueue.offer(image)
    }

    /**
     * FFmpeg プロセスと writer thread を安全に停止し、リソースを解放します。
     */
    fun stop() {
        running = false
        writerThread?.interrupt()
        try {
            writerThread?.join(1000)
        } catch (_: InterruptedException) {
        }
        try {
            ffmpegInput?.close()
        } catch (_: IOException) {
        }
        ffmpegProcess?.destroy()
        ffmpegProcess = null
        ffmpegInput = null
        writerThread = null
        frameQueue.clear()
    }
}