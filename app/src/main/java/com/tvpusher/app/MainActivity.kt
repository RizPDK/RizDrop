
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
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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
                    status = "Streaming to TV..."
                    val ok = pushFile(tvIp, uri)
                    status = if (ok) "✓ Transfer Complete!" else "✗ Transfer Failed"
                }
            }

            Column(modifier = Modifier.padding(24.dp)) {
               Text("RizDrop", style = MaterialTheme.typography.headlineMedium)
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

            Dadb.create(ip, 5555).use { client ->
                client.push(temp, "/sdcard/Download/$name")
            }
            temp.delete()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
