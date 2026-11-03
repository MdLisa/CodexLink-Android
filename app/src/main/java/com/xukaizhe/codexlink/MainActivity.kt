package com.xukaizhe.codexlink

import android.content.Intent
import android.net.Uri
import java.util.UUID
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider
import com.xukaizhe.codexlink.ui.theme.CodexLinkTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: BridgeViewModel
    private lateinit var updates: AppUpdateViewModel
    private var importReceipt:String?=null
    private var networkAllowed by mutableStateOf(Build.VERSION.SDK_INT < 37)
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){allowed->networkAllowed=allowed;if(allowed)model.startObserving()}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge();model=ViewModelProvider(this)[BridgeViewModel::class.java];checkPermission();receiveFileIntent(intent,savedInstanceState?.getString("importReceipt"))
        updates=ViewModelProvider(this)[AppUpdateViewModel::class.java]
        setContent{CodexLinkTheme{CodexApp(model,networkAllowed,{updates.check()}){permission.launch("android.permission.ACCESS_LOCAL_NETWORK")};AppUpdateDialog(updates)}}
    }
    @Suppress("DEPRECATION")
    private fun receiveFileIntent(source:Intent?,restoredReceipt:String?=null){
        if(source?.action !in setOf(Intent.ACTION_VIEW,Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE))return
        importReceipt=source?.getStringExtra("codexlink.importReceipt")?:restoredReceipt?:UUID.randomUUID().toString()
        source?.putExtra("codexlink.importReceipt",importReceipt)
        val uris=runCatching{
            val candidates=mutableListOf<Uri>()
            if(source?.action==Intent.ACTION_VIEW)source.data?.let{candidates.add(it)}
            if(source?.action==Intent.ACTION_SEND)source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let{candidates.add(it)}
            if(source?.action==Intent.ACTION_SEND_MULTIPLE)source.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let{candidates.addAll(it.take(9))}
            source?.clipData?.let{clip->for(i in 0 until minOf(clip.itemCount,9))clip.getItemAt(i).uri?.let{candidates.add(it)}}
            candidates.distinct().take(9)
        }.getOrDefault(emptyList())
        model.importExternal(uris,requireNotNull(importReceipt))
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);receiveFileIntent(intent)}
    override fun onSaveInstanceState(outState:Bundle){outState.putString("importReceipt",importReceipt);super.onSaveInstanceState(outState)}
    private fun checkPermission(){networkAllowed=Build.VERSION.SDK_INT<37||checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==PackageManager.PERMISSION_GRANTED}
    override fun onStart(){super.onStart();checkPermission();if(networkAllowed)model.startObserving();updates.onForeground()}
    override fun onStop(){model.stopObserving();super.onStop()}
}
