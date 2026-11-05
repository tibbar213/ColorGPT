// SPDX-License-Identifier: GPL-3.0-only
package dev.evoker.homeholdcts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import rikka.shizuku.Shizuku;

public final class WatchService extends Service {
    static volatile String latest="未启动监听";
    private static volatile IWakeWatcher remote;
    static final String RETURN_TO_ORIGIN = "return_to_origin";
    static final String POWER_ENABLED = "power_enabled";
    static final boolean DEFAULT_POWER_ENABLED = true;
    static final String HOTWORD_ENABLED = "hotword_enabled";
    private boolean powerEnabled;
    private boolean hotwordEnabled = true;
    private boolean bound;
    private boolean returnToOrigin;
    private final Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
            new ComponentName("dev.evoker.colorgptshizuku",ShellWatcher.class.getName()))
            .daemon(false).processNameSuffix("shell_watcher").debuggable(false).version(5);
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            remote=IWakeWatcher.Stub.asInterface(binder);
            try { latest=remote.startWatching(returnToOrigin,powerEnabled,hotwordEnabled); } catch(Exception error) { latest="连接失败："+error.getClass().getSimpleName(); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { remote=null; latest="Shizuku 监听连接已断开，请检查后重新启动。"; }
    };
    static String readStatus() {
        IWakeWatcher watcher=remote;
        if(watcher!=null) try { latest=watcher.status(); } catch(Exception ignored) { latest="监听连接不可用。"; }
        return latest;
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent==null || "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        try {
            if(!Shizuku.pingBinder() || !ShellWatcher.supportsUid(Shizuku.getUid()) || Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) {
                latest="拒绝运行：需要已授权的 Shizuku（Root 或 ADB 模式）。"; stopSelf(); return START_NOT_STICKY;
            }
            NotificationManager manager=getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("wake_watch","系统唤醒转接",NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,WatchService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE);
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,ShizukuActivity.class),PendingIntent.FLAG_IMMUTABLE);
            Notification notification=new Notification.Builder(this,"wake_watch").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("ColorGPT：正在转接系统唤醒").setContentText("仅 Shizuku；点击查看，停止后恢复小布")
                    .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
            startForeground(3171,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            returnToOrigin=intent.getBooleanExtra(RETURN_TO_ORIGIN,false);
            powerEnabled=intent.getBooleanExtra(POWER_ENABLED,DEFAULT_POWER_ENABLED);
            hotwordEnabled=intent.getBooleanExtra(HOTWORD_ENABLED,true);
            if(remote!=null) latest=remote.startWatching(returnToOrigin,powerEnabled,hotwordEnabled);
            if(!bound) { Shizuku.bindUserService(args,connection); bound=true; latest="等待 Shizuku 监听服务连接…"; }
        } catch(Exception error) { latest="启动失败："+error.getClass().getSimpleName(); stopSelf(); }
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        IWakeWatcher watcher=remote;
        if(watcher!=null) try { watcher.stopWatching(); } catch(Exception ignored) {}
        remote=null;
        if(bound) try { Shizuku.unbindUserService(args,connection,true); } catch(Exception ignored) {}
        bound=false; latest="监听已停止"; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
