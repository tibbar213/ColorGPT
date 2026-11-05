// SPDX-License-Identifier: GPL-3.0-only
package dev.evoker.homeholdcts;

import android.os.Binder;
import android.os.Process;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed-purpose Shizuku user service. Never accepts shell commands from IPC. */
public final class ShellWatcher extends IWakeWatcher.Stub {
    private final int ownerUid;
    private final int wakeUid;
    private volatile boolean running;
    private volatile boolean returnToOrigin;
    private volatile boolean powerEnabled;
    private volatile boolean hotwordEnabled = true;
    private volatile String state="已连接，尚未监听";
    private volatile int generation;
    private volatile int matched, launched;
    private DirectLogdReader reader;
    private ExecutorService actions;
    private final AtomicBoolean busy=new AtomicBoolean();

    public ShellWatcher() {
        if(!supportsUid(Process.myUid())) throw new SecurityException("Unsupported Shizuku service UID");
        ownerUid=packageUid("dev.evoker.colorgptshizuku");
        wakeUid=packageUid("com.oplus.ovoicemanager.wakeup");
        if(ownerUid<10000 || wakeUid<10000) throw new SecurityException("Unexpected package UID");
    }
    static boolean supportsUid(int uid) { return uid==0 || uid==2000; }
    private void checkCaller() {
        int uid=Binder.getCallingUid();
        if(uid!=ownerUid && uid!=Process.myUid()) throw new SecurityException("Foreign caller");
    }
    @Override public synchronized String startWatching(boolean returnToOrigin,boolean powerEnabled,boolean hotwordEnabled) {
        checkCaller();
        this.returnToOrigin=returnToOrigin;
        this.powerEnabled=powerEnabled;
        this.hotwordEnabled=hotwordEnabled;
        if(running) return status();
        int token=++generation;
        DirectLogdReader next=new DirectLogdReader();
        try { next.open(); } catch(Exception error) { state="无法读取系统日志："+error.getClass().getSimpleName(); return status(); }
        reader=next; running=true; busy.set(false);
        actions=Executors.newSingleThreadExecutor();
        long since=System.currentTimeMillis();
        ExecutorService queue=actions;
        state="监听中（等待系统唤醒事件）";
        new Thread(() -> watch(next,queue,token,since),"oem-wake-logd").start();
        return status();
    }
    private void watch(DirectLogdReader stream,ExecutorService queue,int token,long since) {
        long wake=-10000,core=-10000;
        long power=-1,assist=-1,previousAt=-1,lastPowerTransfer=-1;
        Task previous=null;
        try {
            while(active(token)) {
                String line=stream.readRelevantLine();
                if(line==null) throw new IllegalStateException("Log stream closed");
                if(stream.getLastEntryMillis()<since) continue;
                int uid=stream.getLastEntryUid();
                long now=SystemClock.elapsedRealtime();
                if(!hotwordEnabled) wake=core=-10000;
                if(hotwordEnabled && uid==wakeUid && (line.contains("wakeUpSpeechAssist() called.") || line.contains("wakeUpSpeechAssist caller_package: com.oplus.ovoicemanager.wakeup"))) wake=now;
                if(hotwordEnabled && uid==1000 && line.contains("act=heytap.intent.action.ACTIVATE_SPEECH_ASSIST") && line.contains("cmp=com.heytap.speechassist/.core.SpeechService")) core=now;
                long stamp=stream.getLastEntryMillis();
                if(!powerEnabled) {
                    power=assist=previousAt=-1;
                    previous=null;
                } else if(uid==1000) {
                    if(isPowerLongPress(line) && (lastPowerTransfer<0 || stamp-lastPowerTransfer>750)) power=stamp;
                    if(line.startsWith("TaskFragmentExtImpl: shouldSetReadyFalse and resumedActivity: ActivityRecord{")
                            && line.substring(0, line.indexOf(" prev: ")<0 ? line.length() : line.indexOf(" prev: ")).contains(" u0 "+BOBO_FLOAT+" ")
                            && line.endsWith("transitionType: 1")) {
                        Matcher match=PREVIOUS.matcher(line);
                        if(match.find()) {
                            previous=new Task(match.group(1),0,match.group(2),Integer.parseInt(match.group(3)));
                            previousAt=stamp;
                            // TaskFragment records the incoming float task before START is logged.
                            assist=stamp;
                        }
                    }
                    if(line.startsWith("ActivityTaskManager: START u0 {act=android.intent.action.ASSIST ")
                            && line.contains("cmp="+BOBO_FLOAT+" ")
                            && (line.endsWith("result code=0") || line.endsWith("result code=2"))) assist=stamp;
                }
                boolean hotword=hotwordEnabled && wake>=0 && core>=0 && Math.abs(wake-core)<=2500;
                boolean powerPress=powerEnabled && power>=0 && assist>=0 && Math.abs(power-assist)<=750;
                if(!hotword && !powerPress) continue;
                Task powerOrigin=powerPress && previousAt>=0 && Math.abs(previousAt-assist)<=750 ? previous : null;
                if(hotword) wake=core=-10000;
                if(powerPress) { lastPowerTransfer=stamp; power=assist=previousAt=-1; previous=null; }
                matched++;
                if(busy.compareAndSet(false,true)) {
                    queue.execute(() -> {
                        try { transfer(token,now,powerPress,powerOrigin); }
                        finally { busy.set(false); }
                    });
                }
            }
        } catch(Exception error) {
            if(active(token)) { state="监听已中断："+error.getClass().getSimpleName()+"；请停止后重新启动"; running=false; queue.shutdownNow(); }
        } finally { stream.close(); }
    }
    private static boolean isPowerLongPress(String line) {
        return line.startsWith("KEYLOG_SinglePowerKeyMonitor: PowerKey:onLongPress")
                && !line.contains("underWaterCameraStatus = true")
                || line.startsWith("KEYLOG_SingleKeyGesture: ") && line.contains("Detect long press KEYCODE_POWER");
    }
    private boolean active(int token) { return running && generation==token && !Thread.currentThread().isInterrupted(); }
    private void transfer(int token,long event,boolean powerPress,Task powerOrigin) {
        FutureTask<Boolean> voiceEntry = null;
        try {
            if(!active(token) || !(powerPress ? powerEnabled : hotwordEnabled) || SystemClock.elapsedRealtime()-event>3000) return;
            if(!powerPress && !command("/system/bin/am","get-current-user").trim().equals("0")) { state="跳过：当前不是主用户"; return; }
            if(powerPress) {
                voiceEntry = new FutureTask<>(ShellWatcher::hasVoiceEntry);
                new Thread(voiceEntry, "colorgpt-voice-entry-check").start();
            }
            String policy=command("/system/bin/dumpsys","window","policy");
            if(powerPress && !Pattern.compile("(?m)^\\s*mCurrentUserId=0\\s*$").matcher(policy).find()
                    && !command("/system/bin/am","get-current-user").trim().equals("0")) {
                state="跳过：当前不是主用户";
                return;
            }
            boolean verifyOriginAfterStop=returnToOrigin && powerPress && powerOrigin!=null;
            Task origin=returnToOrigin && !verifyOriginAfterStop ? captureOrigin(null) : null;
            if(!policy.contains("mIsShowing=false") || policy.contains("mIsShowing=true")) { state="跳过：手机已锁定或无法确认解锁"; return; }
            if(!active(token) || !(powerPress ? powerEnabled : hotwordEnabled)) return;
            if(!(voiceEntry==null ? hasVoiceEntry() : voiceEntry.get(9,TimeUnit.SECONDS))) {
                state="未找到 ChatGPT 语音入口，已保留小布";
                return;
            }
            if(!active(token) || !(powerPress ? powerEnabled : hotwordEnabled)) return;
            if(SystemClock.elapsedRealtime()-event>3000) return;
            if(powerPress) android.util.Log.i("ColorGPT", "Power handoff checks ready in "
                    +(SystemClock.elapsedRealtime()-event)+" ms after dispatch signal");
            state=powerPress ? "已收到电源键长按，正在关闭小布" : "已收到热词唤醒，正在关闭小布";
            command("/system/bin/am","force-stop","--user","0","com.heytap.speechassist");
            long deadline=SystemClock.elapsedRealtime()+3000;
            while(!command("/system/bin/pidof","com.heytap.speechassist").trim().isEmpty()) {
                if(!active(token)) return;
                if(SystemClock.elapsedRealtime()>deadline) throw new IllegalStateException("Bobo is still running");
                Thread.sleep(80); // Bounded process-exit confirmation; not a voice-start delay.
            }
            if(!active(token)) return;
            if(powerPress) android.util.Log.i("ColorGPT", "Power handoff stopped Bobo in "
                    +(SystemClock.elapsedRealtime()-event)+" ms after dispatch signal");
            if(verifyOriginAfterStop) origin=captureOrigin(null);
            if(origin!=null && (!isCurrent(origin) || (verifyOriginAfterStop && !origin.equals(powerOrigin)))) origin=null;
            String result=command("/system/bin/am","start","-W","--user","0","-a","android.intent.action.VIEW","-d","https://chat.com/c?mode=voice","-p","com.openai.chatgpt","-f","0x10020000","--es","com.openai.feature.shortcut.impl.ShortcutInitializer.EXTRA_SHORTCUT","launcher_shortcut");
            if(!result.contains("Status: ok")) throw new IllegalStateException("Voice launch not accepted");
            launched++;
            state="监听中；普通语音入口已打开，实际连通请以回答为准";
            if(origin!=null) returnWhenReady(token,origin);
        } catch(Exception error) {
            if(active(token)) state="本次转接失败："+error.getClass().getSimpleName()+"（监听仍在）";
        } finally {
            if(voiceEntry!=null) voiceEntry.cancel(true);
        }
    }
    private static final String BOBO_FLOAT="com.heytap.speechassist/.business.lockscreen.FloatSpeechActivity";
    private static final Pattern PREVIOUS=Pattern.compile(" prev: ActivityRecord\\{(\\S+) u0 (\\S+) t(\\d+)\\}");
    private static final String GPT="com.openai.chatgpt";
    private static final String VOICE_SERVICE="com.openai.voice.webrtc.VoiceModeForegroundService";
    private static final Pattern TOP=Pattern.compile("topResumedActivity=ActivityRecord\\{(\\S+) u(\\d+) (\\S+) t(\\d+)");
    private record Task(String record, int user, String component, int id) {
        boolean isGptMain() {
            return component.equals(GPT+"/.MainActivity") || component.equals(GPT+"/"+GPT+".MainActivity");
        }
    }
    private static String tasks() { return command("/system/bin/dumpsys","activity","activities"); }
    private static Task top(String dump) {
        Matcher match=TOP.matcher(dump);
        if(!match.find()) return null;
        Task task=new Task(match.group(1),Integer.parseInt(match.group(2)),match.group(3),Integer.parseInt(match.group(4)));
        return match.find() ? null : task;
    }
    private static boolean isCurrent(Task task) {
        try { return task.equals(top(tasks())); }
        catch(RuntimeException error) { return false; }
    }
    private static Task captureOrigin(Task powerOrigin) {
        try {
            Task task=top(tasks());
            if(task!=null && task.user==0 && task.component.equals(BOBO_FLOAT)) task=powerOrigin;
            if(task==null || task.user!=0 || task.component.startsWith(GPT+"/")
                    || task.component.startsWith("com.heytap.speechassist/")) return null;
            // An existing voice session cannot establish readiness for this launch.
            return voiceForeground() ? null : task;
        } catch(RuntimeException error) { return null; }
    }
    private static boolean hasVoiceEntry() {
        String resolved=command("/system/bin/cmd","package","resolve-activity","--brief","--user","0",
                "-a","android.intent.action.VIEW","-d","https://chat.com/c?mode=voice","-p",GPT);
        return resolved.lines().anyMatch(line -> line.trim().startsWith(GPT+"/"));
    }
    private static boolean voiceForeground() {
        String dump=command("/system/bin/dumpsys","activity","services",GPT);
        int start=dump.indexOf("/"+VOICE_SERVICE+" ");
        if(start<0) return false;
        int end=dump.indexOf("* ServiceRecord{",start);
        return dump.substring(start,end<0 ? dump.length() : end).contains("isForeground=true");
    }
    private static boolean voiceActive(Pattern mic,Pattern mode) {
        String dump=command("/system/bin/dumpsys","audio");
        return mic.matcher(dump).find() && mode.matcher(dump).find() && voiceForeground();
    }
    private void returnWhenReady(int token,Task origin) {
        try {
            int uid=packageUid(GPT);
            Pattern mic=Pattern.compile("(?m)^\\s*session:\\d+ -- source client=VOICE_COMMUNICATION[^\\r\\n]*-- uid:"
                    +uid+" --[^\\r\\n]*pack:com\\.openai\\.chatgpt[^\\r\\n]*silenced:false");
            Pattern mode=Pattern.compile("mAudioModeOwner:[^\\r\\n]*mMode=MODE_IN_COMMUNICATION[^\\r\\n]*mUid="+uid+"\\b");
            long deadline=SystemClock.elapsedRealtime()+10000;
            Task voiceTask=null;
            while(active(token) && returnToOrigin && SystemClock.elapsedRealtime()<deadline) {
                Task foreground=top(tasks());
                if(foreground==null || foreground.user!=0) return;
                if(voiceTask==null && foreground.component.equals(GPT+"/.ChatGptDeeplinkActivity")) {
                    Thread.sleep(250);
                    continue;
                }
                if(!foreground.isGptMain()) return;
                if(voiceTask!=null && !voiceTask.equals(foreground)) return;
                voiceTask=foreground;
                if(!voiceActive(mic,mode)) {
                    Thread.sleep(250);
                    continue;
                }
                String policy=command("/system/bin/dumpsys","window","policy");
                String dump=tasks();
                boolean originExists=Pattern.compile("ActivityRecord\\{"+Pattern.quote(origin.record)
                        +" u0 "+Pattern.quote(origin.component)+" t"+origin.id+"(?:\\s|\\})").matcher(dump).find();
                if(!originExists || !voiceTask.equals(top(dump)) || !policy.contains("mIsShowing=false")
                        || policy.contains("mIsShowing=true") || !active(token) || !returnToOrigin
                        || SystemClock.elapsedRealtime()>=deadline) return;
                command("/system/bin/am","task","focus",Integer.toString(origin.id));
                Task current=top(tasks());
                state=current!=null && current.id==origin.id && voiceActive(mic,mode)
                        ? "已返回原应用，ChatGPT 语音仍在后台运行"
                        : "已请求返回原应用，后台语音状态未确认";
                return;
            }
            if(active(token)) state="未确认语音就绪，保留 ChatGPT 前台";
        } catch(Exception error) {
            if(active(token)) state="自动返回验证未完成："+error.getClass().getSimpleName();
        }
    }
    @Override public synchronized String stopWatching() {
        checkCaller(); running=false; generation++;
        if(reader!=null) { reader.close(); reader=null; }
        if(actions!=null) { actions.shutdownNow(); actions=null; }
        state="已停止，不再转接后续唤醒";
        return status();
    }
    @Override public String status() {
        checkCaller(); return "服务 UID="+Process.myUid()+"\n"+state+"\n配对唤醒="+matched+"；入口启动成功="+launched;
    }
    @Override public void destroy() { checkCaller(); stopWatching(); System.exit(0); }
    private static int packageUid(String name) {
        String text=command("/system/bin/cmd","package","list","packages","-U","--user","0",name);
        for(String line:text.split("\\R")) {
            String prefix="package:"+name+" uid:";
            if(line.startsWith(prefix)) return Integer.parseInt(line.substring(prefix.length()).trim());
        }
        throw new IllegalStateException("Package not installed");
    }
    private static String command(String... args) {
        java.lang.Process child=null;
        try {
            child=new ProcessBuilder(args).redirectErrorStream(true).start();
            java.lang.Process captured=child;
            CompletableFuture<byte[]> output=CompletableFuture.supplyAsync(() -> {
                try { return captured.getInputStream().readAllBytes(); }
                catch(Exception error) { throw new CompletionException(error); }
            });
            if(!child.waitFor(8,TimeUnit.SECONDS)) throw new IllegalStateException("Command timeout");
            String text=new String(output.get(1,TimeUnit.SECONDS),StandardCharsets.UTF_8);
            if(child.exitValue()!=0 && !(args[0].endsWith("/pidof") && child.exitValue()==1)) throw new IllegalStateException("Command rejected");
            return text;
        } catch(Exception error) { throw new IllegalStateException(error); }
        finally { if(child!=null) child.destroyForcibly(); }
    }
}
