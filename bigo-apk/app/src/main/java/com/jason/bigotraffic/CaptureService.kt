package com.jason.bigotraffic

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

class CaptureService : Service() {
    companion object { const val ACTION_STOP="STOP"; const val ACTION_SAMPLE="com.jason.bigotraffic.SAMPLE"; const val EXTRA_VIEWERS="viewers"; const val EXTRA_RAW="raw"; private const val CH="tracking"; private const val NOTIF=11 }
    private var projection: MediaProjection?=null; private var imageReader: ImageReader?=null; private var virtualDisplay: android.hardware.display.VirtualDisplay?=null
    private val handler=Handler(Looper.getMainLooper()); private val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS); private lateinit var db:TrafficDb; private var busy=false; private var intervalMs=30_000L
    private val tick=object:Runnable { override fun run(){ captureOnce(); handler.postDelayed(this,intervalMs) } }
    override fun onCreate(){ super.onCreate(); db=TrafficDb(this); createChannel() }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_STOP){ stopTracking(); stopSelf(); return START_NOT_STICKY }
        if(projection==null){
            val resultCode=intent?.getIntExtra("resultCode",Activity.RESULT_CANCELED)?:Activity.RESULT_CANCELED
            @Suppress("DEPRECATION") val data:Intent?=if(Build.VERSION.SDK_INT>=33) intent?.getParcelableExtra("data",Intent::class.java) else intent?.getParcelableExtra("data")
            intervalMs=intent?.getLongExtra("intervalMs",30_000L)?:30_000L
            if(resultCode!=Activity.RESULT_OK||data==null){stopSelf();return START_NOT_STICKY}
            startForeground(NOTIF,notification("Reading BIGO traffic…")); val mgr=getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection=mgr.getMediaProjection(resultCode,data); projection?.registerCallback(object:MediaProjection.Callback(){override fun onStop(){stopTracking(false);stopSelf()}},handler); setupDisplay(); handler.postDelayed(tick,1500L)
        }
        return START_NOT_STICKY
    }
    private fun setupDisplay(){ val dm=resources.displayMetrics; val width=dm.widthPixels; val height=dm.heightPixels; imageReader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2); virtualDisplay=projection?.createVirtualDisplay("BigoTrafficCapture",width,height,dm.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,imageReader?.surface,null,handler) }
    private fun captureOnce(){
        if(busy)return; val image=imageReader?.acquireLatestImage()?:return; busy=true
        try { val plane=image.planes[0]; val pixelStride=plane.pixelStride; val rowStride=plane.rowStride; val rowPadding=rowStride-pixelStride*image.width; val padded=Bitmap.createBitmap(image.width+rowPadding/pixelStride,image.height,Bitmap.Config.ARGB_8888); padded.copyPixelsFromBuffer(plane.buffer); val full=Bitmap.createBitmap(padded,0,0,image.width,image.height); padded.recycle()
            val p=getSharedPreferences("cfg",MODE_PRIVATE); val x=max(0,(full.width*p.getFloat("roiL",.78f)).toInt()); val y=max(0,(full.height*p.getFloat("roiT",.04f)).toInt()); val right=min(full.width,(full.width*p.getFloat("roiR",.94f)).toInt()); val bottom=min(full.height,(full.height*p.getFloat("roiB",.11f)).toInt()); val crop=Bitmap.createBitmap(full,x,y,max(1,right-x),max(1,bottom-y)); full.recycle()
            recognizer.process(InputImage.fromBitmap(crop,0)).addOnSuccessListener{result-> val raw=result.text.trim(); val value=chooseViewerNumber(raw); if(value!=null)db.insert(value); sendBroadcast(Intent(ACTION_SAMPLE).setPackage(packageName).apply{putExtra(EXTRA_VIEWERS,value?:-1);putExtra(EXTRA_RAW,raw)})}.addOnCompleteListener{crop.recycle();busy=false}
        } catch(_:Throwable){busy=false} finally{image.close()}
    }
    private fun chooseViewerNumber(raw:String):Int?{ val m=Pattern.compile("(?<!\\d)(\\d{1,6})(?!\\d)").matcher(raw.replace(",","")); val values=mutableListOf<Int>(); while(m.find())m.group(1)?.toIntOrNull()?.let{values+=it}; return values.lastOrNull{it in 1..999999} }
    private fun stopTracking(stopProjection:Boolean=true){handler.removeCallbacks(tick);virtualDisplay?.release();virtualDisplay=null;imageReader?.close();imageReader=null;if(stopProjection)projection?.stop();projection=null}
    override fun onDestroy(){stopTracking();recognizer.close();super.onDestroy()}
    private fun createChannel(){if(Build.VERSION.SDK_INT>=26)(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CH,"Traffic tracking",NotificationManager.IMPORTANCE_LOW))}
    private fun notification(text:String):Notification{val stop=Intent(this,CaptureService::class.java).setAction(ACTION_STOP);val pi=PendingIntent.getService(this,0,stop,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);return NotificationCompat.Builder(this,CH).setContentTitle("BIGO Traffic Tracker").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).addAction(android.R.drawable.ic_media_pause,"Stop",pi).build()}
    override fun onBind(intent:Intent?)=null
}
