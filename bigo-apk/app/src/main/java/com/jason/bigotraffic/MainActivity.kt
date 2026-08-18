package com.jason.bigotraffic

import android.Manifest
import android.app.Activity
import android.content.*
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jason.bigotraffic.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var db: TrafficDb
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            saveRoi()
            val i = Intent(this, CaptureService::class.java).apply { putExtra("resultCode", res.resultCode); putExtra("data", res.data); putExtra("intervalMs", selectedInterval()) }
            ContextCompat.startForegroundService(this, i)
            b.tvStatus.text = "Status: Tracking — switch to BIGO"
        } else b.tvStatus.text = "Status: Screen-capture permission denied"
    }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val viewers = intent?.getIntExtra(CaptureService.EXTRA_VIEWERS, -1) ?: -1
            val raw = intent?.getStringExtra(CaptureService.EXTRA_RAW).orEmpty()
            b.tvLastRead.text = "Last OCR: ${if (raw.isBlank()) "(nothing detected)" else raw.replace("\n", " | ")}"
            if (viewers > 0) b.tvCurrent.text = "Current traffic: $viewers"
            refreshStats()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root); db = TrafficDb(this)
        b.spInterval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("10 seconds", "30 seconds", "60 seconds", "2 minutes")); b.spInterval.setSelection(1)
        loadRoi(); refreshStats()
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        b.btnStart.setOnClickListener { val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager; captureLauncher.launch(mgr.createScreenCaptureIntent()) }
        b.btnStop.setOnClickListener { startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)); b.tvStatus.text = "Status: Stopped" }
        b.btnExport.setOnClickListener { exportCsv() }
    }
    override fun onStart() { super.onStart(); ContextCompat.registerReceiver(this, receiver, IntentFilter(CaptureService.ACTION_SAMPLE), ContextCompat.RECEIVER_NOT_EXPORTED) }
    override fun onStop() { unregisterReceiver(receiver); super.onStop() }
    private fun selectedInterval(): Long = when (b.spInterval.selectedItemPosition) { 0 -> 10_000L; 1 -> 30_000L; 2 -> 60_000L; else -> 120_000L }
    private fun saveRoi() {
        fun pct(s: String, d: Float) = (s.toFloatOrNull()?.div(100f) ?: d).coerceIn(0f, 1f)
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putFloat("roiL", pct(b.etRoiLeft.text.toString(), .78f)).putFloat("roiT", pct(b.etRoiTop.text.toString(), .04f)).putFloat("roiR", pct(b.etRoiRight.text.toString(), .94f)).putFloat("roiB", pct(b.etRoiBottom.text.toString(), .11f)).apply()
    }
    private fun loadRoi() { val p = getSharedPreferences("cfg", MODE_PRIVATE); b.etRoiLeft.setText((p.getFloat("roiL", .78f)*100).toInt().toString()); b.etRoiTop.setText((p.getFloat("roiT", .04f)*100).toInt().toString()); b.etRoiRight.setText((p.getFloat("roiR", .94f)*100).toInt().toString()); b.etRoiBottom.setText((p.getFloat("roiB", .11f)*100).toInt().toString()) }
    private fun refreshStats() { val (count, peak, avg) = db.stats(); b.tvSamples.text = "Samples: ${count ?: 0}"; b.tvPeak.text = "Peak traffic: ${peak ?: "—"}"; b.tvAverage.text = if (avg == null) "Average traffic: —" else "Average traffic: %.1f".format(avg); db.latest()?.let { b.tvCurrent.text = "Current traffic: ${it.viewers}" } }
    private fun exportCsv() { val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()); val file = File(getExternalFilesDir(null), "bigo_traffic_${System.currentTimeMillis()}.csv"); file.bufferedWriter().use { w -> w.appendLine("timestamp,viewers"); db.all().forEach { w.appendLine("${fmt.format(Date(it.ts))},${it.viewers}") } }; b.tvStatus.text = "CSV saved: ${file.absolutePath}" }
}
