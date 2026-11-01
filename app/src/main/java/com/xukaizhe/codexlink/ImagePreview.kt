package com.xukaizhe.codexlink

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

private data class ImageLoad(val bitmap:ImageBitmap?=null,val error:String?=null)
private fun decodeImage(bytes:ByteArray,maxDimension:Int):ImageBitmap {
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    require(bounds.outWidth>0&&bounds.outHeight>0){"无法识别这张图片。"}
    var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>maxDimension)sample*=2
    return (BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?:error("无法读取这张图片。")).asImageBitmap()
}
@Composable private fun imageLoad(photo:ImageAttachment,vm:BridgeViewModel,attempt:Int,fullSize:Boolean):ImageLoad {
    val load by produceState(ImageLoad(),photo.id,photo.available,attempt,fullSize){
        value=ImageLoad()
        if(!photo.available||photo.id.isBlank()){value=ImageLoad(error="图片已不可用");return@produceState}
        value=try{val bytes=vm.loadImage(photo.id);ImageLoad(bitmap=withContext(Dispatchers.IO){decodeImage(bytes,if(fullSize)2048 else 512)})}
        catch(e:CancellationException){throw e}
        catch(e:Exception){ImageLoad(error=when(e){is BridgeException,is IllegalArgumentException,is IllegalStateException->e.message?:"图片加载失败，请重试。";is IOException->"图片加载失败，请检查连接后重试。";else->"图片加载失败，请重试。"})}
    }
    return load
}
@Composable fun HistoryImage(photo:ImageAttachment,vm:BridgeViewModel,open:()->Unit){
    var attempt by remember(photo.id){mutableIntStateOf(0)}
    val load=imageLoad(photo,vm,attempt,false)
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Box(Modifier.size(width=220.dp,height=160.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(enabled=load.bitmap!=null,onClick=open).testTag("historyImage"),contentAlignment=Alignment.Center){
            when{
                load.bitmap!=null->Image(load.bitmap,"已发送图片，点击查看大图",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                load.error!=null->Column(Modifier.padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(load.error,style=MaterialTheme.typography.bodySmall);if(photo.available)TextButton(onClick={attempt++}){Text("重试")}}
                else->CircularProgressIndicator(Modifier.size(28.dp))
            }
        }
        if(load.bitmap!=null)Text("点击查看大图",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
    }
}
@Composable fun ImagePreview(photo:ImageAttachment,vm:BridgeViewModel,dismiss:()->Unit){
    var attempt by remember(photo.id){mutableIntStateOf(0)}
    var scale by remember(photo.id){mutableFloatStateOf(1f)}
    var offset by remember(photo.id){mutableStateOf(Offset.Zero)}
    var viewport by remember{mutableStateOf(IntSize.Zero)}
    val load=imageLoad(photo,vm,attempt,true)
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().testTag("imagePreview")){
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick=dismiss){Text("关闭",color=Color.White)}
                Spacer(Modifier.weight(1f))
                if(scale>1f)TextButton(onClick={scale=1f;offset=Offset.Zero}){Text("还原",color=Color.White)}
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged{viewport=it}
                .pointerInput(photo.id){detectTransformGestures{_,pan,zoom,_->
                    val next=(scale*zoom).coerceIn(1f,6f)
                    val x=viewport.width*(next-1f)/2;val y=viewport.height*(next-1f)/2
                    offset=if(next==1f)Offset.Zero else Offset((offset.x+pan.x).coerceIn(-x,x),(offset.y+pan.y).coerceIn(-y,y));scale=next
                }}
                .pointerInput(photo.id){detectTapGestures(onDoubleTap={scale=if(scale>1f)1f else 2.5f;offset=Offset.Zero})},contentAlignment=Alignment.Center){
                when{
                    load.bitmap!=null->Image(load.bitmap,"图片大图",Modifier.fillMaxSize().graphicsLayer{scaleX=scale;scaleY=scale;translationX=offset.x;translationY=offset.y},contentScale=ContentScale.Fit)
                    load.error!=null->Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(load.error,color=Color.White);if(photo.available)TextButton(onClick={attempt++}){Text("重试",color=Color.White)}}
                    else->CircularProgressIndicator(color=Color.White)
                }
            }
            Text("双指缩放 · 拖动查看 · 双击放大或还原",Modifier.align(Alignment.CenterHorizontally).padding(14.dp),color=Color.White.copy(alpha=0.7f),style=MaterialTheme.typography.labelSmall)
        }
    }
}
