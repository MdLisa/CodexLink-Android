package com.xukaizhe.codexlink

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
    private var networkAllowed by mutableStateOf(Build.VERSION.SDK_INT < 37)
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){allowed->networkAllowed=allowed;if(allowed)model.startObserving()}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge();model=ViewModelProvider(this)[BridgeViewModel::class.java];checkPermission()
        setContent{CodexLinkTheme{CodexApp(model,networkAllowed){permission.launch("android.permission.ACCESS_LOCAL_NETWORK")}}}
    }
    private fun checkPermission(){networkAllowed=Build.VERSION.SDK_INT<37||checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==PackageManager.PERMISSION_GRANTED}
    override fun onStart(){super.onStart();checkPermission();if(networkAllowed)model.startObserving()}
    override fun onStop(){model.stopObserving();super.onStop()}
}