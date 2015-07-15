package com.mvgv70.xposed_mcu;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.util.Log;

public class Main implements IXposedHookLoadPackage {
	
  private final static String TAG = "xposed-mcu";
  // private final static int MCU_ACCOFF = 138;
  private final static int MCU_GO_SLEEP = 240;
  // private final static int MCU_ACCON = 139;
  private final static int MCU_WAKE_UP = 241;
  private final static int MCU_ILLUMINATION = 147;
  private static int shutdownDelay = 5;
  private final static String BRIGHTNESS_EVENT = "com.mvg_v70.brightness";
  private static Service microntekServer = null;
  private AudioManager am;
  // задержка выключения запущена
  private static volatile boolean isShutdown = false;
  // процедура выключения выполнена
  private static volatile boolean didShutdown = false;
  // запущено ли радио
  private static boolean isRadioRunning;
  private static Thread do_shutdown;
	
  @Override
  public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
    
    // MicrontekServer.onCreate()
    XC_MethodHook onCreate = new XC_MethodHook() {
      
      @Override
      protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        Log.d(TAG,"onCreate");
        microntekServer = (Service)param.thisObject;
        am = ((AudioManager)microntekServer.getSystemService(Context.AUDIO_SERVICE));
        // переключение приложений
        IntentFilter ai = new IntentFilter();
        ai.addAction("com.microntek.canbusdisplay");
        microntekServer.registerReceiver(appsReceiver, ai);
      }
    };
    
    // MicrontekServer.cmdProc(byte[], int, int)
    XC_MethodHook cmdProc = new XC_MethodHook() {
      
      @Override
      protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        // параметры
        byte[] paramArray = (byte[])param.args[0];
        int index = (int)param.args[1];
        @SuppressWarnings("unused")
        int extra = (int)param.args[2];
        // непонятно зачем это
        int val = 0xFF & paramArray[(index+1)];
        if (val != 151) Log.d(TAG,"cmd="+val);
        // обработка
        if (val == MCU_GO_SLEEP)
        {
          Log.d(TAG,"MCU_GO_SLEEP");
          PowerOff();
          // не вызываем штатный обработчик
          param.setResult(null);
        }
        else if (val == MCU_WAKE_UP)
        {
          Log.d(TAG,"MCU_WAKE_UP");
          if (PowerOn())
            // прервали thread, штатный обработчик не вызываем
            param.setResult(null);
          else
        	// вызываем штатный обработчик, если процесс завершения уже выполнен
            Log.d(TAG,"call powerOn");
        }
        else if (val == MCU_ILLUMINATION)
        {
          Log.d(TAG,"MCU_ILLUMINATION");
          // уведомляем сервис mtc-volume и включении/выключении габаритов
          Intent intent = new Intent(BRIGHTNESS_EVENT);
          microntekServer.sendBroadcast(intent);
        }
      }
    };
    
    // begin hooks
    if (!lpparam.packageName.equals("android.microntek.service")) return;
    Log.d(TAG,"android.microntek.service");
    XposedHelpers.findAndHookMethod("android.microntek.service.MicrontekServer", lpparam.classLoader, "cmdProc", byte[].class, int.class, int.class, cmdProc);
    XposedHelpers.findAndHookMethod("android.microntek.service.MicrontekServer", lpparam.classLoader, "onCreate", onCreate);
    Log.d(TAG,"android.microntek.service hook OK");
  }
  
  private void PowerOff()
  {
    if (isShutdown)
    {
      // защита от повторного вызова
      Log.d(TAG,"shutdown process already running");
      return;
    }
    isShutdown = true;
    didShutdown = false;
    //
    do_shutdown = new Thread("delay_shutdown")
    {
      public void run()
      {
        Log.d(TAG,"shutdown thread running, sleep "+shutdownDelay);
        try 
        {
          sleep(shutdownDelay*1000);
        } 
        catch (InterruptedException e) 
        { 
          Log.d(TAG,"thread interrupted"); 
        }
        Log.d(TAG,"shutdown thread ending");
        // если thread не был прерван в PowerOn()
        if (isShutdown)
        {
          // вызываем оригинальный метод powerOff()
          didShutdown = true;
          Log.d(TAG,"do shutdown: call powerOff");
          XposedHelpers.callMethod(microntekServer, "powerOff", new Object[] {});
          Log.d(TAG,"powerOff called");
        }
        else
        {
          Log.d(TAG,"shutdown process interrupted");
        }
      }
    };
    Log.d(TAG,"isRadioRunning="+isRadioRunning);
    if (isRadioRunning)
    {
      // отключаем радио
      am.setParameters("ctl_radio_mute=true");
      am.setParameters("av_channel_exit=fm");
    }
    // иначе не получим MCU_WAKE_UP
    am.setParameters("rpt_power=false");
    Log.d(TAG,"starting shutdown delay thread");
    do_shutdown.start();
  }
  
  private boolean PowerOn()
  {
    isShutdown = false;
    if(!didShutdown)
    {
      Log.d(TAG,"interrupt shutdown");
      do_shutdown.interrupt();
      am.setParameters("rpt_power=true");
      do_shutdown = null;
      // включаем радио, если оно было включено
      if (isRadioRunning)
      {
    	// именно в таком порядке
        am.setParameters("av_channel_enter=fm");
        am.setParameters("ctl_radio_mute=false");
      }
      // не вызываем штатный обработчик
      return true;
    }
    else
      // завершение работы уже выполнено
      return false;
  }
  
  // обработчик переключения приложений
  private BroadcastReceiver appsReceiver = new BroadcastReceiver()
  {
	  
    public void onReceive(Context context, Intent intent)
    {
      String event = intent.getStringExtra("type");
      Log.d(TAG,"appsReceiver "+event);
      // запущено ли радио
      if (event.endsWith("-on"))
      {
        isRadioRunning = event.equals("radio-on");
        Log.d(TAG,"isRadioRunning "+isRadioRunning);
      }
      else if (event.endsWith("-off"))
      {
        isRadioRunning = !event.equals("radio-off");
        Log.d(TAG,"isRadioRunning "+isRadioRunning);
      }
    }
  };

}