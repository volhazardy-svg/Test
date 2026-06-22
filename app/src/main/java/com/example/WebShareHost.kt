package com.example

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

data class SharedFile(
    val uri: Uri,
    val name: String,
    val size: Long
)

/**
 * A ultra-lightweight, 100% standard Java/Kotlin raw ServerSocket HTTP Server
 * that allows iOS, macOS, Windows, or adjacent Android devices to send and receive
 * files through a modern, elegant web interface on the same local Wi-Fi / Hotspot network.
 */
class WebShareHost(
    private val context: Context,
    private val port: Int = 8989,
    private val onStatusUpdate: (String) -> Unit,
    private val onFileReceived: (File) -> Unit,
    private val onLogMessage: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    // State of the current file selected for sharing
    var sharedFileUri: Uri? = null
    var sharedFileName: String = ""
    var sharedFileSize: Long = 0

    val sharedFiles = mutableListOf<SharedFile>()

    fun start() {
        if (isRunning) return
        try {
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
            }
            isRunning = true
            onStatusUpdate("Active")
            onLogMessage("Web Portal started on port $port")

            thread(name = "web-share-thread") {
                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        thread {
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            onLogMessage("Server accept error: ${e.localizedMessage}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            onStatusUpdate("Failed")
            onLogMessage("Fail starting server: ${e.localizedMessage}")
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        onStatusUpdate("Stopped")
        onLogMessage("Web Portal stopped.")
    }

    private fun handleClient(socket: Socket) {
        var outputStream: BufferedOutputStream? = null
        try {
            val bufferedIn = java.io.BufferedInputStream(socket.getInputStream())
            val lineReader = SimpleLineReader(bufferedIn)
            outputStream = BufferedOutputStream(socket.getOutputStream())

            // Read command line
            val reqLine = lineReader.readLine() ?: return
            onLogMessage("Request: $reqLine")

            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val decodedPath = URLDecoder.decode(parts[1], "UTF-8")

            // Read HTTP headers
            var contentLength = 0L
            var boundaryStr: String? = null
            var userAgentStr = "Web Browser"
            var line: String?
            while (lineReader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                val lowerLine = line!!.lowercase()
                if (lowerLine.startsWith("content-length:")) {
                    contentLength = line!!.substring(15).trim().toLongOrNull() ?: 0L
                } else if (lowerLine.startsWith("content-type:") && lowerLine.contains("boundary=")) {
                    val boundaryIdx = line!!.indexOf("boundary=")
                    if (boundaryIdx != -1) {
                        var b = line!!.substring(boundaryIdx + 9).trim()
                        if (b.contains(";")) {
                            b = b.substring(0, b.indexOf(";")).trim()
                        }
                        if (b.startsWith("\"") && b.endsWith("\"")) {
                            b = b.substring(1, b.length - 1)
                        } else if (b.startsWith("'") && b.endsWith("'")) {
                            b = b.substring(1, b.length - 1)
                        }
                        boundaryStr = "--" + b
                    }
                } else if (lowerLine.startsWith("user-agent:")) {
                    val rawUa = line!!.substring(11).trim()
                    userAgentStr = when {
                        rawUa.contains("iPhone") -> "iPhone"
                        rawUa.contains("iPad") -> "iPad"
                        rawUa.contains("Android") -> "Android Phone"
                        rawUa.contains("Windows") -> "Windows PC"
                        rawUa.contains("Macintosh") || rawUa.contains("OS X") -> "MacBook/macOS"
                        rawUa.contains("Linux") -> "Linux PC"
                        else -> "Web Browser"
                    }
                }
            }

            val clientIp = socket.inetAddress.hostAddress ?: "Unknown IP"

            if (method == "GET") {
                if (decodedPath.startsWith("/download")) {
                    serveFileDownload(outputStream, clientIp, userAgentStr, decodedPath)
                } else if (decodedPath.endsWith(".png") || decodedPath.endsWith(".jpg") || decodedPath.contains("favicon")) {
                    // Ignore asset logs
                    serveWebConsole(outputStream)
                } else {
                    onLogMessage("PORTAL_ACTION|$clientIp|$userAgentStr|Connected/Browsed Portal")
                    serveWebConsole(outputStream)
                }
            } else if (method == "POST" && decodedPath.startsWith("/upload")) {
                handleFileUpload(bufferedIn, contentLength, boundaryStr, outputStream, clientIp, userAgentStr)
            } else {
                serveError(outputStream, "404 Not Found", "The requested path does not exist.")
            }
        } catch (e: Exception) {
            Log.e("WebShareHost", "Client handling failed", e)
            onLogMessage("Web Error: ${e.localizedMessage}")
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun serveWebConsole(out: BufferedOutputStream) {
        val hasFileStr = if (sharedFiles.isNotEmpty()) {
            val listHtml = java.lang.StringBuilder()
            sharedFiles.forEachIndexed { index, file ->
                listHtml.append("""
            <div class="active-file-card" style="margin-bottom: 12px;">
                <div class="file-icon-pulse">
                    <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline></svg>
                </div>
                <div style="flex:1; margin-right: 8px;">
                    <div class="file-name">${escapeHtml(file.name)}</div>
                    <div class="file-meta">${formatFileSize(file.size)}</div>
                </div>
                <a href="/download?index=$index" class="download-btn">
                     <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:8px"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
                     Download
                </a>
            </div>
                """.trimIndent())
            }
            listHtml.toString()
        } else if (sharedFileUri != null) {
            """
            <div class="active-file-card">
                <div class="file-icon-pulse">
                    <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline></svg>
                </div>
                <div style="flex:1; margin-right: 8px;">
                    <div class="file-name">${escapeHtml(sharedFileName)}</div>
                    <div class="file-meta">${formatFileSize(sharedFileSize)}</div>
                </div>
                <a href="/download" class="download-btn">
                     <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:8px"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
                     Download
                </a>
            </div>
            """
        } else {
            """
            <div class="empty-state">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" class="empty-icon" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>
                <p>No files are currently active for broadcast.</p>
                <p style="font-size:12px; color:#938f99; margin-top:4px">Select a file on the Android App to broadcast it instantly.</p>
            </div>
            """
        }

        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Transfr Web Console</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                        background: #141318;
                        color: #E6E1E9;
                        padding: 24px 16px;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        min-height: 100vh;
                    }
                    .app-container {
                        width: 100%;
                        max-width: 440px;
                        background: #211F26;
                        border-radius: 28px;
                        border: 1px solid #49454F;
                        padding: 28px;
                        box-shadow: 0 12px 32px rgba(0,0,0,0.5);
                    }
                    header {
                        display: flex;
                        align-items: center;
                        margin-bottom: 24px;
                        gap: 12px;
                    }
                    .logo-box {
                        background: #4F378B;
                        width: 44px;
                        height: 44px;
                        border-radius: 12px;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        color: #EADDFF;
                    }
                    h1 { font-size: 20px; font-weight: 700; color: #E6E1E9; }
                    .subtitle { font-size: 11px; color: #938F99; text-transform: uppercase; letter-spacing: 1.5px; }
                    
                    .section-title {
                        font-size: 12px;
                        font-weight: 800;
                        color: #D0BCFF;
                        letter-spacing: 1.2px;
                        text-transform: uppercase;
                        margin-bottom: 12px;
                        margin-top: 24px;
                    }
                    
                    .active-file-card {
                        background: #1D1B20;
                        border: 1px solid #49454F;
                        border-radius: 20px;
                        padding: 16px;
                        display: flex;
                        align-items: center;
                        gap: 12px;
                    }
                    .file-icon-pulse {
                        width: 44px;
                        height: 44px;
                        border-radius: 50%;
                        background: rgba(208, 188, 255, 0.1);
                        color: #D0BCFF;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                    }
                    .file-name { font-weight: 600; font-size: 14px; word-break: break-all; color: #E6E1E9; }
                    .file-meta { font-size: 11px; color: #938F99; margin-top: 2px; }
                    .download-btn {
                        background: #4F378B;
                        color: #EADDFF;
                        text-decoration: none;
                        border: none;
                        padding: 10px 14px;
                        border-radius: 12px;
                        font-size: 13px;
                        font-weight: 600;
                        display: inline-flex;
                        align-items: center;
                        transition: background 0.2s;
                    }
                    .download-btn:hover { background: #654E99; }
                    
                    .upload-card {
                        background: #1D1B20;
                        border: 2px dashed #49454F;
                        border-radius: 20px;
                        padding: 24px;
                        text-align: center;
                        cursor: pointer;
                        position: relative;
                        transition: border-color 0.2s;
                    }
                    .upload-card:hover { border-color: #D0BCFF; }
                    .upload-icon { color: #938F99; margin-bottom: 10px; }
                    .upload-title { font-size: 14px; font-weight: 600; color: #E6E1E9; }
                    .upload-desc { font-size: 11px; color: #938F99; margin-top: 4px; }
                    .file-input {
                        position: absolute;
                        left: 0; top: 0; width: 100%; height: 100%;
                        opacity: 0; cursor: pointer;
                    }
                    
                    .empty-state {
                        text-align: center;
                        padding: 24px 0;
                        color: #938F99;
                    }
                    .empty-icon { color: rgba(147, 143, 153, 0.3); margin-bottom: 8px; }
                    .empty-state p { font-size: 13px; }
                    
                    .progress-overlay {
                        display: none;
                        position: fixed;
                        top: 0; left: 0; right: 0; bottom: 0;
                        background: rgba(20, 19, 24, 0.9);
                        z-index: 1000;
                        flex-direction: column;
                        justify-content: center;
                        align-items: center;
                        padding: 24px;
                    }
                    .spinner {
                        width: 48px;
                        height: 48px;
                        border: 4px solid rgba(208, 188, 255, 0.1);
                        border-top-color: #D0BCFF;
                        border-radius: 50%;
                        animation: spin 1s infinite linear;
                        margin-bottom: 16px;
                    }
                    @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
                    .status-heading { font-weight: 700; font-size: 18px; margin-bottom: 6px; }
                    .status-detail { font-size: 13px; color: #938F99; }
                </style>
            </head>
            <body>
                <div class="app-container">
                    <header>
                        <div class="logo-box">
                             <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="17 1 21 5 17 9"></polyline><path d="M3 11V9a4 4 0 0 1 4-4h14"></path><polyline points="7 23 3 19 7 15"></polyline><path d="M21 13v2a4 4 0 0 1-4 4H3"></path></svg>
                        </div>
                        <div>
                            <h1>Transfr Web</h1>
                            <div class="subtitle">Platform Bridge</div>
                        </div>
                    </header>

                    <div class="section-title">Received Shared File</div>
                    $hasFileStr

                    <div class="section-title">Send to Android</div>
                    <form id="uploadForm" action="/upload" method="POST" enctype="multipart/form-data">
                        <div class="upload-card">
                            <svg class="upload-icon" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
                            <div class="upload-title">Choose local media or file</div>
                            <div class="upload-desc">Supports photos, camera shots & documents directly</div>
                            <input class="file-input" type="file" name="file" onchange="submitUpload()" required />
                        </div>
                    </form>
                </div>

                <div class="progress-overlay" id="loadingOverlay">
                    <div class="spinner"></div>
                    <div class="status-heading">Uploading File...</div>
                    <div class="status-detail" id="uploadProgressDetail">Connecting with Android device...</div>
                </div>

                <script>
                    function submitUpload() {
                        document.getElementById('loadingOverlay').style.display = 'flex';
                        document.getElementById('uploadProgressDetail').innerText = 'Streaming file data securely...';
                        document.getElementById('uploadForm').submit();
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        val rawBytes = html.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 200 OK\r\n".toByteArray())
        out.write("Content-Type: text/html; charset=utf-8\r\n".toByteArray())
        out.write("Content-Length: ${rawBytes.size}\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.write(rawBytes)
        out.flush()
    }

    private fun serveFileDownload(out: BufferedOutputStream, clientIp: String, userAgent: String, path: String) {
        var uri = sharedFileUri
        var fileName = sharedFileName
        var fileSize = sharedFileSize

        if (sharedFiles.isNotEmpty()) {
            val idx = if (path.contains("index=")) {
                path.substringAfter("index=").substringBefore("&").toIntOrNull() ?: 0
            } else {
                0
            }
            if (idx in 0 until sharedFiles.size) {
                val f = sharedFiles[idx]
                uri = f.uri
                fileName = f.name
                fileSize = f.size
            }
        }

        if (uri == null) {
            serveError(out, "400 Bad Request", "No active broadcast file available.")
            return
        }

        var inputStream: InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                serveError(out, "404 Not Found", "File stream could not be loaded locally.")
                return
            }

            out.write("HTTP/1.1 200 OK\r\n".toByteArray())
            out.write("Content-Type: application/octet-stream\r\n".toByteArray())
            out.write("Content-Length: $fileSize\r\n".toByteArray())
            out.write("Content-Disposition: attachment; filename=\"$fileName\"\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.flush()

            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var totalSent = 0L
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                out.write(buffer, 0, bytesRead)
                totalSent += bytesRead
            }
            out.flush()
            onLogMessage("Sent file $fileName successfully ($totalSent bytes)")
            onLogMessage("PORTAL_ACTION|$clientIp|$userAgent|Downloaded: $fileName")
        } catch (e: Exception) {
            onLogMessage("Error sending $fileName: ${e.localizedMessage}")
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
        }
    }

    private fun handleFileUpload(
        rawIn: InputStream,
        contentLength: Long,
        boundary: String?,
        out: BufferedOutputStream,
        clientIp: String,
        userAgent: String
    ) {
        if (boundary == null || contentLength <= 0) {
            serveError(out, "400 Bad Request", "Invalid multipart request content details.")
            return
        }

        // We will read the entire multipart content, find the filename, then download the file payload
        var tempFile: File? = null
        var outputStream: FileOutputStream? = null
        try {
            // Find a unique filename
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            
            // To parse a multipart body reliably:
            // Let's read line declarations of the first multi-part headers
            val lineReader = SimpleLineReader(rawIn)
            
            // 1. Read boundary line
            var currentLine = lineReader.readLine() ?: ""
            if (!currentLine.contains(boundary)) {
                serveError(out, "400 Bad Request", "Multipart boundary line mismatch.")
                return
            }

            // 2. Read headers of the file parameter
            var originalFilename = "web_received_file"
            while (true) {
                val hLine = lineReader.readLine() ?: break
                if (hLine.isEmpty()) break
                val lower = hLine.lowercase()
                if (lower.contains("content-disposition:") && lower.contains("filename=")) {
                    val fnIdx = hLine.indexOf("filename=")
                    if (fnIdx != -1) {
                        var fn = hLine.substring(fnIdx + 9).trim()
                        if (fn.startsWith("\"") && fn.endsWith("\"")) {
                            fn = fn.substring(1, fn.length - 1)
                        } else if (fn.startsWith("'") && fn.endsWith("'")) {
                            fn = fn.substring(1, fn.length - 1)
                        }
                        originalFilename = fn
                    }
                }
            }

            // Save target file
            var file = File(downloadsDir, originalFilename)
            var count = 1
            val ext = file.extension
            val baseName = file.nameWithoutExtension
            while (file.exists()) {
                file = File(downloadsDir, "$baseName-$count.$ext")
                count++
            }
            tempFile = file
            outputStream = FileOutputStream(tempFile)

            // 3. Read body content directly until concluding boundary.
            // Match the boundary itself (without requiring preceding CRLF which varies across mobile browsers like iOS Safari).
            // Any trailing blank lines/newlines before boundary will be stripped from the final file afterwards.
            val boundaryBytes = boundary.toByteArray(Charsets.US_ASCII)
            var totalContentReadBytes = 0L
            var matchIdx = 0
            var incomingByte: Int
            var isPayloadPhase = true
            var bytesWritten = 0L

            while (totalContentReadBytes < contentLength) {
                incomingByte = rawIn.read()
                if (incomingByte == -1) break
                totalContentReadBytes++

                if (isPayloadPhase) {
                    // Check if matching boundary prefix
                    if (incomingByte == boundaryBytes[matchIdx].toInt()) {
                        matchIdx++
                        if (matchIdx == boundaryBytes.size) {
                            // Boundary found! Payload has finished
                            isPayloadPhase = false
                        }
                    } else {
                        // Match failed! Flush previously prospective matched bytes, then the failed byte
                        if (matchIdx > 0) {
                            for (i in 0 until matchIdx) {
                                outputStream.write(boundaryBytes[i].toInt())
                                bytesWritten++
                            }
                            matchIdx = 0
                        }
                        
                        // Re-evaluate current byte in case it starts the boundary pattern
                        if (incomingByte == boundaryBytes[0].toInt()) {
                            matchIdx = 1
                        } else {
                            outputStream.write(incomingByte)
                            bytesWritten++
                        }
                    }
                }
            }

            outputStream.flush()
            outputStream.close()
            outputStream = null

            // Cleanly strip trailing boundary blank lines/newlines (CR or LF) from the files received
            if (tempFile != null && tempFile.exists()) {
                var fileLength = tempFile.length()
                val randomAccessFile = java.io.RandomAccessFile(tempFile, "rw")
                try {
                    while (fileLength > 0) {
                        randomAccessFile.seek(fileLength - 1)
                        val lastb = randomAccessFile.read()
                        if (lastb == 10 || lastb == 13) {
                            fileLength--
                        } else {
                            break
                        }
                    }
                    randomAccessFile.setLength(fileLength)
                } catch (_: Exception) {
                } finally {
                    try { randomAccessFile.close() } catch (_: Exception) {}
                }
                bytesWritten = tempFile.length()
            }

            if (tempFile.exists() && bytesWritten > 0) {
                onFileReceived(tempFile)
                onLogMessage("Uploaded file: ${tempFile.name} (${formatFileSize(bytesWritten)})")
                onLogMessage("PORTAL_ACTION|$clientIp|$userAgent|Uploaded: ${tempFile.name}")

                // Serve Success page back
                val successHtml = """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>Upload Complete</title>
                        <style>
                            body { font-family: -apple-system, sans-serif; background: #141318; color: #E6E1E9; display: flex; justify-content: center; align-items: center; min-height: 100vh; padding: 20px; }
                            .card { width: 100%; max-width: 400px; background: #211F26; border-radius: 28px; border: 1px solid #49454F; padding: 28px; text-align: center; box-shadow: 0 12px 32px rgba(0,0,0,0.5); }
                            .success-icon { display: inline-flex; width: 64px; height: 64px; border-radius: 50%; background: rgba(39, 174, 96, 0.1); color: #27AE60; justify-content: center; align-items: center; margin-bottom: 20px; }
                            h1 { font-size: 20px; margin-bottom: 8px; color: #E6E1E9; }
                            p { font-size: 13px; color: #938F99; line-height: 1.5; margin-bottom: 24px; }
                            .btn { background: #4F378B; color: #EADDFF; border: none; text-decoration: none; padding: 12px 24px; border-radius: 12px; font-weight: 600; font-size: 14px; display: inline-block; cursor: pointer; }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <div class="success-icon">
                                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"></polyline></svg>
                            </div>
                            <h1>Upload Successful!</h1>
                            <p>Masterfully transferred <strong>${escapeHtml(originalFilename)}</strong> to your Android Device (${formatFileSize(bytesWritten)}).</p>
                            <a href="/" class="btn">Send Another File</a>
                        </div>
                    </body>
                    </html>
                """.trimIndent()

                val rawSuccessBytes = successHtml.toByteArray(Charsets.UTF_8)
                out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                out.write("Content-Type: text/html; charset=utf-8\r\n".toByteArray())
                out.write("Content-Length: ${rawSuccessBytes.size}\r\n".toByteArray())
                out.write("Connection: close\r\n\r\n".toByteArray())
                out.write(rawSuccessBytes)
                out.flush()
            } else {
                serveError(out, "500 Internal Server Error", "File content empty or corrupt during receiving phase.")
            }
        } catch (e: Exception) {
            outputStream?.close()
            tempFile?.delete()
            serveError(out, "500 Internal Server Error", "Error collecting payload stream: ${e.localizedMessage}")
        }
    }

    private fun serveError(out: BufferedOutputStream, status: String, message: String) {
        val errorHtml = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"><title>Error</title><style>body { font-family:sans-serif; background:#141318; color:#eee; text-align:center; padding:50px; } h1 { color:#E28F8F; }</style></head>
            <body>
                <h1>$status</h1>
                <p>$message</p>
                <p><a href="/" style="color:#D0BCFF">Return Home</a></p>
            </body>
            </html>
        """.trimIndent()
        val rawBytes = errorHtml.toByteArray(Charsets.UTF_8)
        try {
            out.write("HTTP/1.1 $status\r\n".toByteArray())
            out.write("Content-Type: text/html; charset=utf-8\r\n".toByteArray())
            out.write("Content-Length: ${rawBytes.size}\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.write(rawBytes)
            out.flush()
        } catch (_: Exception) {}
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        val index = if (digitGroups < units.size) digitGroups else units.size - 1
        return String.format("%.1f %s", size / Math.pow(1024.0, index.toDouble()), units[index])
    }

    private fun escapeHtml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
    }

    /**
     * Minimal custom Reader that reads byte-by-byte from raw InputStream
     * to prevent consuming binary payload files when reading HTTP header lines.
     */
    private class SimpleLineReader(private val stream: InputStream) {
        fun readLine(): String? {
            val out = ByteArrayOutputStream()
            var lastByte = -1
            while (true) {
                val b = stream.read()
                if (b == -1) {
                    if (out.size() == 0) return null
                    break
                }
                if (b == 10 && lastByte == 13) { // '\n' after '\r'
                    val bytes = out.toByteArray()
                    // Strip the trailing '\r' byte
                    return String(bytes, 0, bytes.size - 1, Charsets.UTF_8)
                }
                out.write(b)
                lastByte = b
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
