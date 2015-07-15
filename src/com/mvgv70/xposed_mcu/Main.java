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
  // �������� ���������� ��������
  private static volatile boolean isShutdown = false;
  // ��������� ���������� ���������
  private static volatile boolean didShutdown = false;
  // �������� �� �����
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
        // ������������ ����������
        IntentFilter ai = new IntentFilter();
        ai.addAction("com.microntek.canbusdisplay");
        microntekServer.registerReceiver(appsReceiver, ai);
      }
    };
    
    // MicrontekServer.cmdProc(byte[], int, int)
    XC_MethodHook cmdProc = new XC_MethodHook() {
      
      @Override
      protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        // ���������
        byte[] paramArray = (byte[])param.args[0];
        int index = (int)param.args[1];
        @SuppressWarnings("unused")
        int extra = (int)param.args[2];
        // ��������� ����� ���
        int val = 0xFF & paramArray[(index+1)];
        if (val != 151) Log.d(TAG,"cmd="+val);
        // ���������
        if (val == MCU_GO_SLEEP)
        {
          Log.d(TAG,"MCU_GO_SLEEP");
          PowerOff();
          // �� �������� ������� ����������
          param.setResult(null);
        }
        else if (val == MCU_WAKE_UP)
        {
          Log.d(TAG,"MCU_WAKE_UP");
          if (PowerOn())
            // �������� thread, ������� ���������� �� ��������
            param.setResult(null);
          else
        	// �������� ������� ����������, ���� ������� ���������� ��� ��������
            Log.d(TAG,"call powerOn");
        }
        else if (val == MCU_ILLUMINATION)
        {
          Log.d(TAG,"MCU_ILLUMINATION");
          // ���������� ������ mtc-volume � ���������/���������� ���������
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
      // ������ �� ���������� ������
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
        // ���� thread �� ��� ������� � PowerOn()
        if (isShutdown)
        {
          // �������� ������������ ����� powerOff()
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
      // ��������� �����
      am.setParameters("ctl_radio_mute=true");
      am.setParameters("av_channel_exit=fm");
    }
    // ����� �� ������� MCU_WAKE_UP
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
      // �������� �����, ���� ��� ���� ��������
      if (isRadioRunning)
      {
    	// ������ � ����� �������
        am.setParameters("av_channel_enter=fm");
        am.setParameters("ctl_radio_mute=false");
      }
      // �� �������� ������� ����������
      return true;
    }
    else
      // ���������� ������ ��� ���������
      return false;
  }
  
  // ���������� ������������ ����������
  private BroadcastReceiver appsReceiver = new BroadcastReceiver()
  {
	  
    public void onReceive(Context context, Intent intent)
    {
      String event = intent.getStringExtra("type");
      Log.d(TAG,"appsReceiver "+event);
      // �������� �� �����
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