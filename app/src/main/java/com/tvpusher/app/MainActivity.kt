package com.tvpusher.app

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var tvIp by remember { mutableStateOf("192.168.1.4") }
            var status by remember { mutableStateOf("Ready") }
            val scope = rememberCoroutineScope()

            val picker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                if (uri == null) return@rememberLauncherForActivityResult
                scope.launch {
                    status = "Transferring file to TV..."
                    val ok = pushFile(tvIp, uri)
                    status = if (ok) "✓ Transfer Complete!" else "✗ Transfer Failed (Check IP/Port 5555)"
                }
            }

            Column(modifier = Modifier.padding(24.dp)) {
                Text("RizDrop 📱 ⚡ 📺", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = tvIp,
                    onValueChange = { tvIp = it },
                    label = { Text("TV IP Address") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { picker.launch("*/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Select File & Send to TV")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Status: $status")
            }
        }
    }

    private suspend fun pushFile(ip: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            var name = "transfer.file"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && col >= 0) name = cursor.getString(col)
            }

            val temp = File(cacheDir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }

            val success = AdbStreamPusher.push(ip, 5555, temp, "/sdcard/Download/$name")
            temp.delete()
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

object AdbStreamPusher {
    private const val A_CNXN = 0x4e584e43
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_WRTE = 0x45545257
    private const val A_CLSE = 0x45534c43

    fun push(ip: String, port: Int, file: File, remotePath: String): Boolean {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), 5000)
            socket.soTimeout = 15000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // 1. Send ADB CNXN Handshake
            val banner = "host::RizDrop\u0000".toByteArray(Charsets.UTF_8)
            writeAdbMessage(output, A_CNXN, 0x01000000, 64 * 1024, banner)

            val header = ByteArray(24)
            input.readFully(header) // Consume device response

            // 2. Open SYNC service
            val localId = 1
            val syncService = "sync:\u0000".toByteArray(Charsets.UTF_8)
            writeAdbMessage(output, A_OPEN, localId, 0, syncService)

            input.readFully(header)
            val respCmd = readIntLE(header, 0)
            if (respCmd != A_OKAY) return false
            val remoteId = readIntLE(header, 4)

            // 3. Send file metadata (SEND)
            val sendCmd = "$remotePath,33206".toByteArray(Charsets.UTF_8)
            val initBuffer = ByteArrayOutputStream()
            initBuffer.write("SEND".toByteArray(Charsets.US_ASCII))
            initBuffer.write(intToLE(sendCmd.size))
            initBuffer.write(sendCmd)
            writeAdbMessage(output, A_WRTE, localId, remoteId, initBuffer.toByteArray())
            input.readFully(header) // Wait for OKAY

            // 4. Stream file chunks (DATA)
            val fileBuffer = ByteArray(32 * 1024)
            FileInputStream(file).use { fis ->
                var read: Int
                while (fis.read(fileBuffer).also { read = it } != -1) {
                    val chunk = ByteArrayOutputStream()
                    chunk.write("DATA".toByteArray(Charsets.US_ASCII))
                    chunk.write(intToLE(read))
                    chunk.write(fileBuffer, 0, read)
                    writeAdbMessage(output, A_WRTE, localId, remoteId, chunk.toByteArray())
                    input.readFully(header) // Wait for OKAY flow-control
                }
            }

            // 5. Send DONE
            val done = ByteArrayOutputStream()
            done.write("DONE".toByteArray(Charsets.US_ASCII))
            done.write(intToLE((System.currentTimeMillis() / 1000).toInt()))
            writeAdbMessage(output, A_WRTE, localId, remoteId, done.toByteArray())
            input.readFully(header)

            // 6. Close stream
            writeAdbMessage(output, A_CLSE, localId, remoteId, ByteArray(0))
            return true
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
