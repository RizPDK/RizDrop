package com.tvpusher.app

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var tvIp by remember { mutableStateOf("") }
            var connectionStatus by remember { mutableStateOf(ConnectionStatus.DISCONNECTED) }
            var isBusy by remember { mutableStateOf(false) }
            var statusMessage by remember { mutableStateOf("Ready. Enter TV IP to connect.") }
            var progressDetail by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()

            val filePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                if (uri == null) return@rememberLauncherForActivityResult
                scope.launch {
                    isBusy = true
                    statusMessage = "Sending file..."
                    val ok = pushSingleFile(tvIp, uri) { name ->
                        progressDetail = "Pushing: $name"
                    }
                    if (ok) {
                        statusMessage = "✓ Transfer Complete!"
                    } else {
                        connectionStatus = ConnectionStatus.FAILED
                        statusMessage = "✗ Connection Lost during file transfer."
                    }
                    progressDetail = ""
                    isBusy = false
                }
            }

            val folderPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { treeUri: Uri? ->
                if (treeUri == null) return@rememberLauncherForActivityResult
                scope.launch {
                    isBusy = true
                    statusMessage = "Reading directory..."
                    val ok = pushFolder(tvIp, treeUri) { current, total, name ->
                        progressDetail = "[$current/$total] $name"
                    }
                    if (ok) {
                        statusMessage = "✓ Folder Transfer Complete!"
                    } else {
                        connectionStatus = ConnectionStatus.FAILED
                        statusMessage = "✗ Connection Lost during folder transfer."
                    }
                    progressDetail = ""
                    isBusy = false
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B),
                    primary = Color(0xFF38BDF8),
                    error = Color(0xFFEF4444)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text(
                                text = "RizDrop",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "📱 ➔ 📺",
                                fontSize = 22.sp
                            )
                        }

                        // 1. Connection Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "1. TV Connection",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = tvIp,
                                    onValueChange = {
                                        tvIp = it.trim()
                                        if (connectionStatus != ConnectionStatus.DISCONNECTED) {
                                            connectionStatus = ConnectionStatus.DISCONNECTED
                                            statusMessage = "IP updated. Tap Connect."
                                        }
                                    },
                                    label = { Text("TV IP Address") },
                                    placeholder = { Text("e.g. 192.168.1.15") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                val buttonColor = when (connectionStatus) {
                                    ConnectionStatus.CONNECTED -> Color(0xFF10B981)
                                    ConnectionStatus.FAILED -> Color(0xFFEF4444)
                                    ConnectionStatus.CONNECTING,
                                    ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.primary
                                }

                                val buttonText = when (connectionStatus) {
                                    ConnectionStatus.CONNECTED -> "✓ TV Connected"
                                    ConnectionStatus.FAILED -> "✗ Connection Failed - Tap to Retry"
                                    ConnectionStatus.CONNECTING -> "Connecting..."
                                    ConnectionStatus.DISCONNECTED -> "Connect to TV"
                                }

                                Button(
                                    onClick = {
                                        if (tvIp.isBlank()) {
                                            statusMessage = "Please enter your TV's IP address."
                                            return@Button
                                        }
                                        scope.launch {
                                            isBusy = true
                                            connectionStatus = ConnectionStatus.CONNECTING
                                            statusMessage = "Connecting to $tvIp:5555..."
                                            val valid = AdbStreamPusher.testConnection(tvIp, 5555)
                                            if (valid) {
                                                connectionStatus = ConnectionStatus.CONNECTED
                                                statusMessage = "✓ Connected to TV!"
                                            } else {
                                                connectionStatus = ConnectionStatus.FAILED
                                                statusMessage = "✗ Connection failed. Check TV IP and Port 5555."
                                            }
                                            isBusy = false
                                        }
                                    },
                                    enabled = !isBusy,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
                                ) {
                                    Text(
                                        text = buttonText,
                                        color = if (connectionStatus == ConnectionStatus.CONNECTING || connectionStatus == ConnectionStatus.DISCONNECTED) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // 2. Transfer Actions Card (Gated)
                        if (connectionStatus == ConnectionStatus.CONNECTED) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        "2. Send to TV",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 16.sp,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))

                                    Button(
                                        onClick = { filePicker.launch("*/*") },
                                        enabled = !isBusy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("📄 Select Single File")
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    FilledTonalButton(
                                        onClick = { folderPicker.launch(null) },
                                        enabled = !isBusy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("📁 Select Entire Folder")
                                    }
                                }
                            }
                        }

                        // 3. Activity & Status Card
                        val cardBorderColor = when (connectionStatus) {
                            ConnectionStatus.FAILED -> Color(0xFFEF4444)
                            ConnectionStatus.CONNECTED -> Color(0xFF10B981)
                            else -> Color(0xFF1E293B)
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1120))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("ACTIVITY MONITOR", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                    
                                    val dotColor = when (connectionStatus) {
                                        ConnectionStatus.CONNECTED -> Color(0xFF10B981)
                                        ConnectionStatus.FAILED -> Color(0xFFEF4444)
                                        ConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.primary
                                        ConnectionStatus.DISCONNECTED -> Color.Gray
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(dotColor, CircleShape)
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = statusMessage,
                                    color = if (connectionStatus == ConnectionStatus.FAILED) Color(0xFFEF4444) else Color.White,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )

                                if (progressDetail.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = progressDetail,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                if (isBusy) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = if (connectionStatus == ConnectionStatus.FAILED) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary,
                                        trackColor = Color(0xFF1E293B)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun pushSingleFile(
        ip: String,
        uri: Uri,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            var name = "transfer.file"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && col >= 0) name = cursor.getString(col)
            }
            onProgress(name)
            val temp = File(cacheDir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            val ok = AdbStreamPusher.push(ip, 5555, temp, "/sdcard/Download/$name")
            temp.delete()
            ok
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private suspend fun pushFolder(
        ip: String,
        treeUri: Uri,
        onProgress: (Int, Int, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, treeUri) ?: return@withContext false
            val folderName = rootDoc.name ?: "TransferredFolder"
            val fileList = mutableListOf<DocumentFile>()

            fun scanDir(dir: DocumentFile) {
                for (doc in dir.listFiles()) {
                    if (doc.isFile) fileList.add(doc)
                    else if (doc.isDirectory) scanDir(doc)
                }
            }
            scanDir(rootDoc)

            var success = true
            fileList.forEachIndexed { index, doc ->
                val fileName = doc.name ?: "file_${index}"
                onProgress(index + 1, fileList.size, fileName)

                val temp = File(cacheDir, fileName)
                contentResolver.openInputStream(doc.uri)?.use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                }

                val ok = AdbStreamPusher.push(ip, 5555, temp, "/sdcard/Download/$folderName/$fileName")
                temp.delete()
                if (!ok) {
                    success = false
                    return@forEachIndexed
                }
            }
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

object AdbStreamPusher {
    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_WRTE = 0x45545257
    private const val A_CLSE = 0x45534c43

    fun testConnection(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 4000)
                socket.soTimeout = 4000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                val banner = "host::RizDrop\u0000".toByteArray(Charsets.UTF_8)
                writeAdbMessage(output, A_CNXN, 0x01000000, 64 * 1024, banner)

                val header = ByteArray(24)
                input.readFully(header)
                val respCmd = readIntLE(header, 0)
                respCmd == A_CNXN || respCmd == A_AUTH || respCmd == A_OKAY
            }
        } catch (e: Exception) {
            false
        }
    }

    fun push(ip: String, port: Int, file: File, remotePath: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 5000)
                socket.soTimeout = 15000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                val banner = "host::RizDrop\u0000".toByteArray(Charsets.UTF_8)
                writeAdbMessage(output, A_CNXN, 0x01000000, 64 * 1024, banner)

                val header = ByteArray(24)
                input.readFully(header)

                val localId = 1
                val syncService = "sync:\u0000".toByteArray(Charsets.UTF_8)
                writeAdbMessage(output, A_OPEN, localId, 0, syncService)

                input.readFully(header)
                val respCmd = readIntLE(header, 0)
                if (respCmd != A_OKAY) return false
                val remoteId = readIntLE(header, 4)

                val sendCmd = "$remotePath,33206".toByteArray(Charsets.UTF_8)
                val initBuffer = ByteArrayOutputStream()
                initBuffer.write("SEND".toByteArray(Charsets.US_ASCII))
                initBuffer.write(intToLE(sendCmd.size))
                initBuffer.write(sendCmd)
                writeAdbMessage(output, A_WRTE, localId, remoteId, initBuffer.toByteArray())
                input.readFully(header)

                val fileBuffer = ByteArray(32 * 1024)
                FileInputStream(file).use { fis ->
                    var read: Int
                    while (fis.read(fileBuffer).also { read = it } != -1) {
                        val chunk = ByteArrayOutputStream()
                        chunk.write("DATA".toByteArray(Charsets.US_ASCII))
                        chunk.write(intToLE(read))
                        chunk.write(fileBuffer, 0, read)
                        writeAdbMessage(output, A_WRTE, localId, remoteId, chunk.toByteArray())
                        input.readFully(header)
                    }
                }

                val done = ByteArrayOutputStream()
                done.write("DONE".toByteArray(Charsets.US_ASCII))
                done.write(intToLE((System.currentTimeMillis() / 1000).toInt()))
                writeAdbMessage(output, A_WRTE, localId, remoteId, done.toByteArray())
                input.readFully(header)

                writeAdbMessage(output, A_CLSE, localId, remoteId, ByteArray(0))
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun writeAdbMessage(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val header = ByteArray(24)
        writeIntLE(header, 0, cmd)
        writeIntLE(header, 4, arg0)
        writeIntLE(header, 8, arg1)
        writeIntLE(header, 12, data.size)
        var crc = 0
        for (b in data) crc += (b.toInt() and 0xFF)
        writeIntLE(header, 16, crc)
        writeIntLE(header, 20, cmd xor -1)

        out.write(header)
        if (data.isNotEmpty()) out.write(data)
        out.flush()
    }

    private fun readIntLE(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
        ((b[offset + 1].toInt() and 0xFF) shl 8) or
        ((b[offset + 2].toInt() and 0xFF) shl 16) or
        ((b[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeIntLE(b: ByteArray, offset: Int, v: Int) {
        b[offset] = (v and 0xFF).toByte()
        b[offset + 1] = ((v shr 8) and 0xFF).toByte()
        b[offset + 2] = ((v shr 16) and 0xFF).toByte()
        b[offset + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun intToLE(v: Int): ByteArray {
        val b = ByteArray(4)
        writeIntLE(b, 0, v)
        return b
    }
}
