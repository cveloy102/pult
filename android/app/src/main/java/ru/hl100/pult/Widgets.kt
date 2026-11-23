package ru.hl100.pult

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.concurrent.Executors

object Widgets {
    val worker=Executors.newFixedThreadPool(2)
    const val COMMAND="ru.hl100.pult.WIDGET_COMMAND"
    fun refresh(context:Context){
        val manager=AppWidgetManager.getInstance(context)
        listOf(PowerWidget::class.java,FullWidget::class.java).forEach{type->manager.getAppWidgetIds(ComponentName(context,type)).forEach{id->update(context,manager,id,type==FullWidget::class.java)}}
    }
    fun update(context:Context,manager:AppWidgetManager,id:Int,full:Boolean){
        val settings=Settings(context);val connection=settings.connection;val mode=normalizeTheme(settings.theme)
        val views=RemoteViews(context.packageName,if(full)R.layout.widget_full else R.layout.widget_power)
        // "system" uses the *_auto drawables (drawable-night holds the dark ones) and day/night text colors,
        // so the launcher switches the widget with the phone's dark mode without an app update.
        fun background(view:Int,light:Int,dark:Int,auto:Int)=views.setInt(view,"setBackgroundResource",when(mode){THEME_LIGHT->light;THEME_DARK->dark;else->auto})
        fun textColor(view:Int,pick:(Palette)->Color){
            if(mode==THEME_SYSTEM)views.setColorInt(view,"setTextColor",pick(palette(THEME_LIGHT)).toArgb(),pick(palette(THEME_DARK)).toArgb())
            else views.setTextColor(view,pick(palette(mode)).toArgb())
        }
        background(R.id.widget_root,R.drawable.widget_light,R.drawable.widget_dark,R.drawable.widget_auto)
        views.setTextViewText(R.id.widget_title,connection?.name?:"Пульт")
        views.setTextViewText(R.id.widget_status,if(connection==null)"Подключи компьютер" else settings.widgetMessage)
        listOf(R.id.widget_title,R.id.widget_lock).forEach{textColor(it){p->p.ink}}
        textColor(R.id.widget_status){it.sub}
        textColor(R.id.widget_shutdown){it.accentInk}
        background(R.id.widget_shutdown,R.drawable.button_light,R.drawable.button_dark,R.drawable.button_auto)
        background(R.id.widget_lock,R.drawable.button_card_light,R.drawable.button_card_dark,R.drawable.button_card_auto)
        views.setOnClickPendingIntent(R.id.widget_title,PendingIntent.getActivity(context,id*10,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        views.setOnClickPendingIntent(R.id.widget_shutdown,PendingIntent.getActivity(context,id*10+1,Intent(context,WidgetConfirmActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        fun command(view:Int,action:String,value:Int?=null){val intent=Intent(context,if(full)FullWidget::class.java else PowerWidget::class.java).setAction(COMMAND).putExtra("command",action);value?.let{intent.putExtra("value",it)};val slot=when(view){R.id.widget_lock->2;R.id.widget_minus->3;else->4};views.setOnClickPendingIntent(view,PendingIntent.getBroadcast(context,id*10+slot,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))}
        command(R.id.widget_lock,"lock")
        if(full){listOf(R.id.widget_minus,R.id.widget_plus).forEach{textColor(it){p->p.ink};background(it,R.drawable.button_card_light,R.drawable.button_card_dark,R.drawable.button_card_auto)};command(R.id.widget_minus,"volumeStep",-5);command(R.id.widget_plus,"volumeStep",5)}
        manager.updateAppWidget(id,views)
    }
    fun execute(context:Context,action:String,value:Int?=null):String {
        val settings=Settings(context);val connection=settings.connection?:throw Exception("Сначала подключи компьютер в Пульте.")
        PultApi.command(connection,action,value)
        val message=if(action=="lock")"Блокировка принята" else if(action=="shutdown")"Выключение принято" else "Громкость изменена"
        settings.widgetMessage=message
        if(action=="volumeStep")try{val status=PultApi.status(connection);status.volume?.let{settings.lastVolume=it;settings.widgetMessage="Последняя громкость: $it%"}}catch(_:Exception){}
        refresh(context);return message
    }
    fun error(context:Context,error:Exception):String {
        val message=if(error is ApiException&&error.status==401)"Доступ отозван — привяжи ПК заново" else "Нет подтверждения от ПК. Проверь подключение."
        Settings(context).widgetMessage=message;refresh(context);return message
    }
}
open class BaseWidget(private val full:Boolean):AppWidgetProvider(){
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray){ids.forEach{Widgets.update(context,manager,it,full)}}
    override fun onReceive(context:Context,intent:Intent){
        super.onReceive(context,intent)
        if(intent.action!=Widgets.COMMAND)return
        val action=intent.getStringExtra("command")?:return
        if(action!in listOf("lock","volumeStep"))return
        val value=if(intent.hasExtra("value"))intent.getIntExtra("value",0)else null
        if(action=="volumeStep"&&value !in listOf(-5,5))return
        val pending=goAsync()
        Widgets.worker.execute{
            try{Widgets.execute(context,action,value)}catch(ex:Exception){Widgets.error(context,ex)}finally{pending.finish()}
        }
    }
}
class PowerWidget:BaseWidget(false)
class FullWidget:BaseWidget(true)
class WidgetConfirmActivity:Activity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(Settings(this).connection==null){startActivity(Intent(this,MainActivity::class.java));finish();return}
        AlertDialog.Builder(this).setTitle("Выключить компьютер?").setMessage("Сохрани открытые документы на ПК.").setNegativeButton("Отмена"){_,_->finish()}.setPositiveButton("Выключить"){_,_->
            Widgets.worker.execute{val message=try{Widgets.execute(this,"shutdown")}catch(ex:Exception){Widgets.error(this,ex)};runOnUiThread{Toast.makeText(this,message,Toast.LENGTH_LONG).show();finish()}}
        }.setOnCancelListener{finish()}.show()
    }
}
